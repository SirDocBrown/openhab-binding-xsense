/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.xsense.internal.handler;

import static org.openhab.binding.xsense.internal.XSenseBindingConstants.STATION_TYPE_SBS50;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.xsense.internal.api.XSenseApiClient;
import org.openhab.binding.xsense.internal.api.XSenseApiException;
import org.openhab.binding.xsense.internal.api.XSenseDeviceState;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.House;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Station;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.StationList;
import org.openhab.binding.xsense.internal.config.XSenseAccountConfiguration;
import org.openhab.binding.xsense.internal.config.XSenseBindingConfiguration;
import org.openhab.binding.xsense.internal.discovery.XSenseCloudDiscoveryService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link XSenseAccountHandler} manages the connection to the X-Sense cloud account: it owns the
 * API client, performs the login and polls the house/station/device inventory.
 *
 * @author Markus Michels - Initial contribution
 * @author Andreas Rothstein - Shared HTTP client, non-blocking dispose
 */
@NonNullByDefault
public class XSenseAccountHandler extends BaseBridgeHandler {

    /**
     * Listener notified after each successful inventory refresh (used by the discovery service).
     */
    public interface CloudDataListener {
        void onCloudDataChanged();
    }

    /**
     * Immutable snapshot of the cloud inventory so that houses and stations are always consistent.
     */
    private static class Inventory {
        static final Inventory EMPTY = new Inventory(List.of(), Map.of());

        final List<House> houses;
        final Map<String, StationList> stationsByHouseId;

        Inventory(List<House> houses, Map<String, StationList> stationsByHouseId) {
            this.houses = houses;
            this.stationsByHouseId = stationsByHouseId;
        }
    }

    private static final long RETRY_DELAY_MIN_SEC = 30;
    private static final long RETRY_DELAY_MAX_SEC = 900;

    private final Logger logger = LoggerFactory.getLogger(XSenseAccountHandler.class);
    private final XSenseApiClient apiClient;
    // Binding level default, queried on use so that later binding configuration changes apply
    private final IntSupplier defaultRefreshInterval;

    private volatile XSenseAccountConfiguration config = new XSenseAccountConfiguration();
    // Only accessed from the synchronized connect()/poll() methods
    private long retryDelay = RETRY_DELAY_MIN_SEC;

    private volatile boolean disposed;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile @Nullable ScheduledFuture<?> pollJob;
    private volatile @Nullable ScheduledFuture<?> reconnectJob;
    private volatile @Nullable CloudDataListener cloudDataListener;

    // Latest inventory snapshot, replaced atomically after each poll
    private volatile Inventory inventory = Inventory.EMPTY;

    public XSenseAccountHandler(Bridge bridge, HttpClient httpClient, IntSupplier defaultRefreshInterval) {
        super(bridge);
        apiClient = new XSenseApiClient(httpClient);
        this.defaultRefreshInterval = defaultRefreshInterval;
    }

    @Override
    public void initialize() {
        disposed = false;
        config = getConfigAs(XSenseAccountConfiguration.class);
        if (config.email.isBlank() || config.password.isBlank()) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.config-error-credentials");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::connect);
    }

    @Override
    public void dispose() {
        // Not synchronized: a poll may be blocked in cloud I/O while holding the monitor. The job
        // fields are volatile and every scheduling method re-checks the flag after assigning its job,
        // so a job scheduled concurrently with dispose() is cancelled by one of the two threads.
        disposed = true;
        stopJob(pollJob);
        pollJob = null;
        stopJob(reconnectJob);
        reconnectJob = null;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            scheduler.execute(this::poll);
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(XSenseCloudDiscoveryService.class);
    }

    public void setCloudDataListener(@Nullable CloudDataListener listener) {
        cloudDataListener = listener;
    }

    /**
     * Returns the houses of the last successful inventory poll.
     */
    public List<House> getHouses() {
        return inventory.houses;
    }

    /**
     * Returns the stations (including attached devices) of the last successful poll, keyed by house id.
     */
    public Map<String, StationList> getStationsByHouseId() {
        return inventory.stationsByHouseId;
    }

    /**
     * Returns the id of this account used in log prefixes: the account thing's UID id segment, so
     * the log does not expose the account email address.
     */
    public String getAccountId() {
        return thing.getUID().getId();
    }

    /**
     * Log line prefix ("xsense-&lt;id&gt;") so all messages for one account can be grepped together,
     * e.g. across the account, home and station handlers.
     */
    private String logId() {
        return "xsense-" + getAccountId();
    }

    /**
     * Triggers an inventory refresh in the background, e.g. on child initialization. Fire-and-forget:
     * callers that need the refreshed inventory before continuing must use {@link #pollNow()} instead.
     */
    public void requestRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            scheduler.execute(this::runQueuedRefresh);
        }
    }

    private synchronized void runQueuedRefresh() {
        refreshQueued.set(false);
        poll();
    }

    /**
     * Runs an inventory refresh synchronously on the calling thread and returns once it completes.
     * Used by the discovery service's manual scan, which otherwise runs {@link #requestRefresh()}
     * asynchronously and would publish results from the stale inventory before the new poll
     * finishes.
     */
    public void pollNow() {
        poll();
    }

    /**
     * Performs the Cognito login and, on success, starts inventory polling. On failure, either
     * gives up (bad credentials) or schedules a reconnect with exponential backoff.
     */
    private synchronized void connect() {
        if (disposed) {
            return;
        }
        String logId = logId();
        try {
            logger.debug("{}: logging in", logId);
            apiClient.login(config.email, config.password);
            retryDelay = RETRY_DELAY_MIN_SEC;
            startPolling();
        } catch (XSenseApiException e) {
            logger.debug("{}: login failed: {}", logId, e.getMessage());
            if (disposed) {
                return;
            }
            if (e.isAuthenticationFailure()) {
                updateStatus(ThingStatusDetail.CONFIGURATION_ERROR,
                        statusText("offline.config-error-login", e.getMessage()));
                // No automatic retry: a new login only succeeds after a configuration change
                return;
            }
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR,
                    statusText("offline.communication-error", e.getMessage()));
            scheduleReconnect();
        }
    }

    private void startPolling() {
        if (disposed) {
            return;
        }
        stopJob(pollJob);
        ScheduledFuture<?> job = scheduler.scheduleWithFixedDelay(this::poll, 0, effectiveRefreshInterval(),
                TimeUnit.SECONDS);
        pollJob = job;
        if (disposed) {
            job.cancel(true);
        }
    }

    /**
     * Returns the polling interval: the thing configuration when set, otherwise the binding
     * configuration default.
     */
    private long effectiveRefreshInterval() {
        int thingInterval = config.refreshInterval;
        if (thingInterval > 0) {
            return Math.max(XSenseBindingConfiguration.MIN_REFRESH_INTERVAL_SEC, thingInterval);
        }
        return defaultRefreshInterval.getAsInt();
    }

    /**
     * Fetches the current house/station inventory, replaces the snapshot atomically and notifies
     * home handlers and the discovery service. On an authentication failure, stops polling and
     * schedules a reconnect; other failures leave polling running for the next attempt.
     */
    private synchronized void poll() {
        if (disposed || !apiClient.isLoggedIn()) {
            // Initial login still pending or session lost, connect()/reconnect handles this case
            return;
        }
        String logId = logId();
        try {
            List<House> newHouses = apiClient.getHouses();
            logger.debug("{}: fetched {} house(s): {}", logId, newHouses.size(),
                    newHouses.stream().map(h -> h.houseId).toList());
            Map<String, StationList> newStations = new HashMap<>();
            for (House house : newHouses) {
                String houseId = house.houseId;
                if (houseId != null) {
                    StationList stationList = apiClient.getStations(houseId);
                    List<Station> stations = stationList.stations;
                    logger.debug("{}: house {}: fetched {} station(s): {}", logId, houseId,
                            stations != null ? stations.size() : 0,
                            stations != null ? stations.stream().map(s -> s.stationSn).toList() : List.of());
                    String region = house.mqttRegion;
                    if (stations != null && region != null) {
                        for (Station station : stations) {
                            readSensorStates(region, station);
                        }
                    }
                    newStations.put(houseId, stationList);
                }
            }
            if (disposed) {
                return;
            }
            inventory = new Inventory(List.copyOf(newHouses), Map.copyOf(newStations));
            retryDelay = RETRY_DELAY_MIN_SEC;
            updateStatus(ThingStatus.ONLINE);
            notifyHomeHandlers();
            CloudDataListener listener = cloudDataListener;
            if (listener != null) {
                listener.onCloudDataChanged();
            }
        } catch (XSenseApiException e) {
            logger.debug("{}: inventory poll failed: {}", logId, e.getMessage());
            if (disposed) {
                return;
            }
            if (e.isAuthenticationFailure()) {
                ScheduledFuture<?> job = pollJob;
                if (job != null) {
                    // May be the job running this poll, so do not interrupt the current thread
                    job.cancel(false);
                }
                pollJob = null;
                updateStatus(ThingStatusDetail.COMMUNICATION_ERROR,
                        statusText("offline.communication-error-session", e.getMessage()));
                scheduleReconnect();
            } else {
                updateStatus(ThingStatusDetail.COMMUNICATION_ERROR,
                        statusText("offline.communication-error", e.getMessage()));
            }
        }
    }

    private void readSensorStates(String region, Station station) throws XSenseApiException {
        String serial = station.stationSn;
        List<Device> devices = station.devices;
        if (!STATION_TYPE_SBS50.equals(station.category) || !Integer.valueOf(1).equals(station.onLine)
                || devices == null || devices.isEmpty() || serial == null) {
            return;
        }
        try {
            var states = apiClient.getDeviceStates(region, serial);
            for (Device device : devices) {
                String deviceSerial = device.deviceSn;
                if (deviceSerial != null) {
                    device.reported = XSenseDeviceState.read(states, deviceSerial);
                }
            }
        } catch (XSenseApiException e) {
            if (e.isAuthenticationFailure()) {
                throw e;
            }
            logger.debug("{}: failed to read station status: {}", logId(), e.getMessage());
        }
    }

    /**
     * Pushes the latest inventory snapshot to every home child handler, matched by houseId.
     */
    private void notifyHomeHandlers() {
        Inventory snapshot = inventory;
        for (Thing thing : getThing().getThings()) {
            ThingHandler handler = thing.getHandler();
            if (handler instanceof XSenseHomeHandler homeHandler) {
                String houseId = homeHandler.getHouseId();
                homeHandler.updateFromInventory(findHouse(snapshot.houses, houseId),
                        snapshot.stationsByHouseId.get(houseId));
            }
        }
    }

    private static @Nullable House findHouse(List<House> houses, String houseId) {
        for (House house : houses) {
            if (houseId.equals(house.houseId)) {
                return house;
            }
        }
        return null;
    }

    /**
     * Schedules the next {@link #connect()} attempt with exponential backoff, doubling the delay
     * up to {@link #RETRY_DELAY_MAX_SEC} on every consecutive failure.
     */
    private void scheduleReconnect() {
        if (disposed) {
            return;
        }
        stopJob(reconnectJob);
        ScheduledFuture<?> job = scheduler.schedule(this::connect, retryDelay, TimeUnit.SECONDS);
        reconnectJob = job;
        if (disposed) {
            job.cancel(true);
        }
        retryDelay = Math.min(retryDelay * 2, RETRY_DELAY_MAX_SEC);
    }

    /**
     * Builds a translatable status description with one argument. Double quotes would terminate the
     * argument list of the "@text" syntax and are therefore replaced.
     */
    private static String statusText(String key, @Nullable String argument) {
        String safeArgument = argument == null ? "" : argument.replace('"', '\'');
        return "@text/" + key + " [\"" + safeArgument + "\"]";
    }

    private static void stopJob(@Nullable ScheduledFuture<?> job) {
        if (job != null) {
            job.cancel(true);
        }
    }
}
