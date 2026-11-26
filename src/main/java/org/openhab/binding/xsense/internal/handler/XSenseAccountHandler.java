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

import static org.openhab.binding.xsense.internal.XSenseBindingConstants.*;

import java.util.ArrayList;
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
import org.openhab.binding.xsense.internal.api.XSenseApiClient;
import org.openhab.binding.xsense.internal.api.XSenseApiException;
import org.openhab.binding.xsense.internal.api.XSenseDeviceState;
import org.openhab.binding.xsense.internal.api.XSenseHttpTransport;
import org.openhab.binding.xsense.internal.api.XSenseStationReport;
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
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * The {@link XSenseAccountHandler} manages the connection to the X-Sense cloud account: it owns the
 * API client, performs the login and polls the house/station/device inventory.
 *
 * @author Markus Michels - Initial contribution
 * @author Andreas Rothstein - Non-blocking dispose, station report assignment
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
    // Length of one retry delay unit (normally one second); shortened by tests
    private final long retryUnitMillis;

    private volatile boolean disposed;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile @Nullable ScheduledFuture<?> pollJob;
    private volatile @Nullable ScheduledFuture<?> reconnectJob;
    private volatile @Nullable CloudDataListener cloudDataListener;

    // Latest inventory snapshot, replaced atomically after each poll
    private volatile Inventory inventory = Inventory.EMPTY;

    public XSenseAccountHandler(Bridge bridge, XSenseHttpTransport transport, IntSupplier defaultRefreshInterval) {
        this(bridge, new XSenseApiClient(transport), defaultRefreshInterval);
    }

    XSenseAccountHandler(Bridge bridge, XSenseApiClient apiClient, IntSupplier defaultRefreshInterval) {
        this(bridge, apiClient, defaultRefreshInterval, 1000);
    }

    XSenseAccountHandler(Bridge bridge, XSenseApiClient apiClient, IntSupplier defaultRefreshInterval,
            long retryUnitMillis) {
        super(bridge);
        this.apiClient = apiClient;
        this.defaultRefreshInterval = defaultRefreshInterval;
        this.retryUnitMillis = retryUnitMillis;
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
    synchronized void connect() {
        if (disposed) {
            return;
        }
        String logId = logId();
        try {
            logger.debug("{}: logging in", logId);
            apiClient.login(config.email, config.password);
            retryDelay = RETRY_DELAY_MIN_SEC;
            startPolling();
        } catch (RuntimeException e) {
            // Must not escape: the connect job would end without status and without a retry
            logger.warn("{}: unexpected error during login: {}", logId, e.getClass().getSimpleName());
            if (!disposed) {
                updateStatus(ThingStatusDetail.COMMUNICATION_ERROR,
                        statusText("offline.communication-error", e.getClass().getSimpleName()));
                scheduleReconnect();
            }
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
    synchronized void poll() {
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
        } catch (RuntimeException e) {
            // Must not escape: an exception thrown by a periodic job cancels all further polls
            logger.warn("{}: unexpected error while polling the X-Sense cloud", logId, e);
            if (!disposed) {
                updateStatus(ThingStatusDetail.COMMUNICATION_ERROR,
                        statusText("offline.communication-error", e.getClass().getSimpleName()));
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
        if (!STATION_TYPE_SBS50.equals(station.category) || station.online() != 1 || devices == null
                || devices.isEmpty() || serial == null) {
            return;
        }
        String logId = logId();
        try {
            JsonObject reported = apiClient.getDeviceStates(region, serial);
            XSenseStationReport.Result result = XSenseStationReport.resolve(reported, devices);
            if (reported.size() == 0) {
                logger.debug("{}: station {} has no reported state", logId, serial);
            }
            if (result.unknownRecords() > 0 || result.conflictingRecords() > 0) {
                logger.debug("{}: station {}: ignored {} record(s) of unknown devices, {} with conflicting ids", logId,
                        serial, result.unknownRecords(), result.conflictingRecords());
            }
            for (Device device : devices) {
                String deviceSerial = device.deviceSn;
                if (deviceSerial == null) {
                    continue;
                }
                XSenseDeviceState state = result.states().get(deviceSerial);
                device.reported = state != null ? state : XSenseDeviceState.UNKNOWN;
                // Diagnosis without the device payload: model, assignment result and missing fields
                String model = device.deviceType;
                logger.debug("{}: station {}, model {}: {}", logId, serial, model,
                        describe(state, result.conflictingFields().getOrDefault(deviceSerial, List.of()),
                                result.ambiguousSerials().contains(deviceSerial),
                                model != null ? thingTypeForDeviceType(model) : null));
            }
        } catch (XSenseApiException e) {
            if (e.isAuthenticationFailure()) {
                throw e;
            }
            logger.debug("{}: failed to read station status: {}", logId, e.getMessage());
        }
    }

    /**
     * Summarizes the report of one device for the debug log: the assignment result, the names of
     * the fields the model is expected to report but did not, and of contradicting fields.
     */
    static String describe(@Nullable XSenseDeviceState state, List<String> conflicts, boolean ambiguousSerial,
            @Nullable ThingTypeUID type) {
        if (ambiguousSerial) {
            return "serial number used by several devices of the station, ignored";
        }
        if (state == null) {
            return "no report";
        }
        if (state.online() == 0) {
            return "reported offline";
        }
        List<String> missing = new ArrayList<>(missingFields(state, type));
        missing.removeAll(conflicts);
        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) {
            parts.add("missing " + String.join(", ", missing));
        }
        if (!conflicts.isEmpty()) {
            parts.add("contradicting " + String.join(", ", conflicts));
        }
        return parts.isEmpty() ? "complete" : String.join("; ", parts);
    }

    static List<String> missingFields(XSenseDeviceState state, @Nullable ThingTypeUID type) {
        List<String> missing = new ArrayList<>();
        if (state.online() < 0) {
            missing.add("online");
        }
        boolean hasAlarm = !THING_TYPE_DOOR.equals(type) && !THING_TYPE_MOTION.equals(type);
        if (hasAlarm && state.alarm() < 0) {
            missing.add("alarmStatus");
        }
        if (state.battery() < 0) {
            missing.add("batInfo");
        }
        if (state.signal() < 0) {
            missing.add("rfLevel");
        }
        if (THING_TYPE_CO.equals(type) || THING_TYPE_COMBO.equals(type)) {
            // coPpmPeak is optional, not every CO detector reports it
            if (state.coPpm() < 0) {
                missing.add("coPpm");
            }
        }
        if (THING_TYPE_DOOR.equals(type) && state.open() < 0) {
            missing.add("isOpen");
        }
        if (THING_TYPE_MOTION.equals(type) && state.motion() < 0) {
            missing.add("isMoved");
        }
        return missing;
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
        ScheduledFuture<?> job = scheduler.schedule(this::connect, retryDelay * retryUnitMillis, TimeUnit.MILLISECONDS);
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

    /**
     * Returns the delay of the next reconnect attempt in retry units (seconds).
     */
    synchronized long nextRetryDelay() {
        return retryDelay;
    }

    /**
     * Returns whether the periodic inventory poll is scheduled.
     */
    boolean isPolling() {
        ScheduledFuture<?> job = pollJob;
        return job != null && !job.isDone();
    }

    private static void stopJob(@Nullable ScheduledFuture<?> job) {
        if (job != null) {
            job.cancel(true);
        }
    }
}
