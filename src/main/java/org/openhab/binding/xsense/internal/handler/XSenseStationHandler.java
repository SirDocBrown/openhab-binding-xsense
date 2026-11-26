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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.xsense.internal.api.XSenseStationReport;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Station;
import org.openhab.binding.xsense.internal.config.XSenseStationConfiguration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;

/**
 * The {@link XSenseStationHandler} represents an X-Sense base station (SBS50). It receives the
 * station state from the home handler and propagates the attached device list to its sensor
 * child handlers.
 *
 * @author Markus Michels - Initial contribution
 * @author Andreas Rothstein - Simplified inventory propagation
 */
@NonNullByDefault
public class XSenseStationHandler extends BaseBridgeHandler {

    // Written in initialize(), read via getStationSn() from the home handler's update path
    private volatile XSenseStationConfiguration config = new XSenseStationConfiguration();

    public XSenseStationHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        config = getConfigAs(XSenseStationConfiguration.class);
        if (config.stationSn.isBlank()) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.config-error-station-sn");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        requestRefresh();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The base station has no channels
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            requestRefresh();
        } else {
            updateStatus(ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    public String getStationSn() {
        return config.stationSn;
    }

    /**
     * Requests an inventory refresh via the home handler.
     */
    public void requestRefresh() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof XSenseHomeHandler homeHandler) {
            homeHandler.requestRefresh();
        }
    }

    /**
     * Applies the current inventory data to this thing and its sensor children. Called by the home
     * handler after each successful inventory poll.
     *
     * @param station the station data, or null when the station is no longer part of the home
     */
    public void updateFromInventory(@Nullable Station station) {
        if (station == null) {
            updateStatus(ThingStatusDetail.GONE, "@text/offline.station-not-found");
            return;
        }
        Map<String, String> properties = new HashMap<>();
        String category = station.category;
        if (category != null) {
            properties.put(Thing.PROPERTY_MODEL_ID, category);
        }
        properties.put(Thing.PROPERTY_SERIAL_NUMBER, config.stationSn);
        properties.put(Thing.PROPERTY_VENDOR, "X-Sense");
        updateProperties(properties);

        boolean online = station.online() == 1;
        if (online) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "@text/offline.station-offline");
        }

        List<Device> devices = station.devices;
        Set<String> ambiguousSerials = XSenseStationReport.ambiguousSerials(devices);
        for (Thing thing : getThing().getThings()) {
            ThingHandler handler = thing.getHandler();
            if (handler instanceof XSenseSensorHandler sensorHandler) {
                if (online && ambiguousSerials.contains(sensorHandler.getDeviceSn())) {
                    // Several children share the configured serial number: the values of neither
                    // child can be published on this thing
                    sensorHandler.ambiguousDevice();
                } else if (online) {
                    sensorHandler.updateFromInventory(findDevice(devices, sensorHandler.getDeviceSn()));
                } else {
                    // An offline station reports no child states; the children must stay offline
                    // instead of being reset to UNKNOWN by every inventory poll
                    sensorHandler.stationOffline();
                }
            }
        }
    }

    private static @Nullable Device findDevice(@Nullable List<Device> devices, String deviceSn) {
        if (devices != null) {
            for (Device device : devices) {
                if (deviceSn.equals(device.deviceSn)) {
                    return device;
                }
            }
        }
        return null;
    }
}
