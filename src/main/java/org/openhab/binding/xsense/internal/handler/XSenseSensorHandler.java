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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.xsense.internal.api.XSenseDeviceState;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;
import org.openhab.binding.xsense.internal.config.XSenseDeviceConfiguration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;

/**
 * The {@link XSenseSensorHandler} represents a smoke or water leak detector attached to an X-Sense
 * base station. It maintains the thing status and properties from the inventory and the measurements
 * from the reported state of the station.
 *
 * @author Markus Michels - Initial contribution
 * @author Andreas Rothstein - Detector measurements
 */
@NonNullByDefault
public class XSenseSensorHandler extends BaseThingHandler {

    private static final int MAX_BATTERY_LEVEL = 3;
    // Battery levels at or below this value are reported as low battery by the X-Sense app
    private static final int LOW_BATTERY_LEVEL = 1;

    // Written in initialize(), read via getDeviceSn() from the station handler's update path
    private volatile XSenseDeviceConfiguration config = new XSenseDeviceConfiguration();

    public XSenseSensorHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        config = getConfigAs(XSenseDeviceConfiguration.class);
        if (config.deviceSn.isBlank()) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.config-error-device-sn");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        requestRefresh();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            requestRefresh();
        }
    }

    /**
     * Requests an inventory refresh via the station handler, so a newly added sensor does not stay
     * UNKNOWN until the next regular poll.
     */
    private void requestRefresh() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof XSenseStationHandler stationHandler) {
            stationHandler.requestRefresh();
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            requestRefresh();
        } else {
            clearMeasurements();
            updateStatus(ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    public String getDeviceSn() {
        return config.deviceSn;
    }

    /**
     * Applies the station inventory and the latest reported measurements.
     *
     * @param device the device data, or null when the device is no longer attached to the station
     */
    public void updateFromInventory(@Nullable Device device) {
        if (device == null) {
            clearMeasurements();
            updateStatus(ThingStatusDetail.GONE, "@text/offline.device-not-found");
            return;
        }
        updateProperties(device);

        XSenseDeviceState report = device.reported;
        if (report.online() == 0) {
            clearMeasurements();
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "@text/offline.sensor-offline");
            return;
        }
        if (XSenseDeviceState.UNKNOWN.equals(report)) {
            // No reported state yet, e.g. the station state could not be read in this poll
            clearMeasurements();
            updateStatus(ThingStatus.UNKNOWN);
            return;
        }
        updateState(alarmChannel(), report.alarm() < 0 ? UnDefType.UNDEF : OnOffType.from(report.alarm() > 0));
        int battery = report.battery();
        updateState(CHANNEL_BATTERY,
                battery < 0 ? UnDefType.UNDEF : new DecimalType(Math.round(battery * 100f / MAX_BATTERY_LEVEL)));
        updateState(CHANNEL_LOW_BATTERY, battery < 0 ? UnDefType.UNDEF : OnOffType.from(battery <= LOW_BATTERY_LEVEL));
        updateState(CHANNEL_SIGNAL, report.signal() < 0 ? UnDefType.UNDEF : new DecimalType(report.signal()));
        updateStatus(report.online() == 1 ? ThingStatus.ONLINE : ThingStatus.UNKNOWN);
    }

    private String alarmChannel() {
        return THING_TYPE_WATER.equals(getThing().getThingTypeUID()) ? CHANNEL_ALARM_WATER : CHANNEL_ALARM_SMOKE;
    }

    private void clearMeasurements() {
        for (String channel : List.of(alarmChannel(), CHANNEL_BATTERY, CHANNEL_LOW_BATTERY, CHANNEL_SIGNAL)) {
            updateState(channel, UnDefType.UNDEF);
        }
    }

    private void updateProperties(Device device) {
        Map<String, String> properties = new HashMap<>();
        String deviceType = device.deviceType;
        if (deviceType != null) {
            properties.put(Thing.PROPERTY_MODEL_ID, deviceType);
        }
        String roomName = device.roomName;
        if (roomName != null) {
            properties.put(PROPERTY_ROOM_NAME, roomName);
        }
        properties.put(Thing.PROPERTY_SERIAL_NUMBER, config.deviceSn);
        properties.put(Thing.PROPERTY_VENDOR, "X-Sense");
        updateProperties(properties);
    }
}
