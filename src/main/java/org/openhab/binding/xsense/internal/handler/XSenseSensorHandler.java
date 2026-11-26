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
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link XSenseSensorHandler} represents a detector or sensor (smoke, CO, combined smoke/CO, heat,
 * water leak, door contact, motion) attached to an X-Sense base station. It maintains the thing status
 * and properties from the inventory and the measurements from the reported state of the station.
 *
 * @author Markus Michels - Initial contribution
 * @author Andreas Rothstein - Detector measurements, CO, heat, door and motion sensors
 */
@NonNullByDefault
public class XSenseSensorHandler extends BaseThingHandler {

    private static final int MAX_BATTERY_LEVEL = 3;
    // Battery levels at or below this value are reported as low battery by the X-Sense app
    private static final int LOW_BATTERY_LEVEL = 1;

    private final Logger logger = LoggerFactory.getLogger(XSenseSensorHandler.class);

    // Written in initialize(), read via getDeviceSn() from the station handler's update path
    private volatile XSenseDeviceConfiguration config = new XSenseDeviceConfiguration();
    // Set when the station explicitly reported the device as offline; only an explicit online report
    // ends this state, as the station keeps old values of a disconnected device in its shadow
    private volatile boolean reportedOffline;
    // Set once the detector reported a CO peak value; kept for the lifetime of the handler so that the
    // channel is not removed again when single reports lack the value
    private volatile boolean coPeakReported;

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
            reportedOffline = false;
            clearMeasurements();
            updateStatus(ThingStatusDetail.GONE, "@text/offline.device-not-found");
            return;
        }
        updateProperties(device);

        XSenseDeviceState report = device.reported;
        if (report.online() == 0) {
            reportedOffline = true;
        } else if (report.online() == 1) {
            reportedOffline = false;
        }
        if (reportedOffline) {
            // Values delivered together with an offline flag are outdated and must not be published
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
        String alarmChannel = alarmChannel();
        if (alarmChannel != null) {
            updateState(alarmChannel, onOff(report.alarm()));
        }
        int battery = report.battery();
        updateState(CHANNEL_BATTERY,
                battery < 0 ? UnDefType.UNDEF : new DecimalType(Math.round(battery * 100f / MAX_BATTERY_LEVEL)));
        updateState(CHANNEL_LOW_BATTERY, battery < 0 ? UnDefType.UNDEF : OnOffType.from(battery <= LOW_BATTERY_LEVEL));
        updateState(CHANNEL_SIGNAL, report.signal() < 0 ? UnDefType.UNDEF : new DecimalType(report.signal()));
        ThingTypeUID thingTypeUID = getThing().getThingTypeUID();
        if (hasCoLevel(thingTypeUID)) {
            updateState(CHANNEL_CO_LEVEL, ppm(report.coPpm()));
            updatePeakChannel(report.coPeak());
        }
        if (THING_TYPE_DOOR.equals(thingTypeUID)) {
            int open = report.open();
            updateState(CHANNEL_CONTACT,
                    open < 0 ? UnDefType.UNDEF : open > 0 ? OpenClosedType.OPEN : OpenClosedType.CLOSED);
        } else if (THING_TYPE_MOTION.equals(thingTypeUID)) {
            updateState(CHANNEL_MOTION, onOff(report.motion()));
        }
        updateStatus(report.online() == 1 ? ThingStatus.ONLINE : ThingStatus.UNKNOWN);
    }

    /**
     * Called when the base station reports that it is offline: the station cannot deliver states of
     * its children, so the sensor is offline as well and its measurements are undefined.
     */
    public void stationOffline() {
        clearMeasurements();
        updateStatus(ThingStatusDetail.BRIDGE_OFFLINE);
    }

    /**
     * Returns the alarm channel of this detector type, or null for sensors without an alarm channel.
     */
    private @Nullable String alarmChannel() {
        return switch (getThing().getThingTypeUID().getId()) {
            case "smoke" -> CHANNEL_ALARM_SMOKE;
            case "water" -> CHANNEL_ALARM_WATER;
            case "co" -> CHANNEL_ALARM_CO;
            case "combo" -> CHANNEL_ALARM_COMBO;
            case "heat" -> CHANNEL_ALARM_HEAT;
            default -> null;
        };
    }

    /**
     * Publishes the CO peak value. Not every detector reports it (e.g. the XC01-M via the SBS50 does
     * not), so the channel is added when a peak value is reported for the first time. A channel that
     * exists from an earlier version but has never received a value from this detector is removed
     * again, unless an item is linked to it.
     */
    private void updatePeakChannel(int peak) {
        boolean present = hasChannel(CHANNEL_CO_PEAK);
        if (peak >= 0) {
            coPeakReported = true;
            if (!present && !addChannel(CHANNEL_CO_PEAK, CHANNEL_TYPE_CO_PEAK)) {
                return;
            }
            updateState(CHANNEL_CO_PEAK, ppm(peak));
        } else if (present) {
            if (!coPeakReported && !isLinked(CHANNEL_CO_PEAK)) {
                removeChannel(CHANNEL_CO_PEAK);
            } else {
                updateState(CHANNEL_CO_PEAK, UnDefType.UNDEF);
            }
        }
    }

    private boolean hasChannel(String channelId) {
        return getThing().getChannel(channelId) != null;
    }

    private boolean addChannel(String channelId, ChannelTypeUID channelTypeUID) {
        ThingHandlerCallback callback = getCallback();
        if (callback == null) {
            return false;
        }
        ChannelUID channelUID = new ChannelUID(getThing().getUID(), channelId);
        Channel channel = callback.createChannelBuilder(channelUID, channelTypeUID).build();
        updateThing(editThing().withChannel(channel).build());
        logger.debug("{}: detector reports a CO peak value, channel {} added", getThing().getUID(), channelId);
        return true;
    }

    private void removeChannel(String channelId) {
        ChannelUID channelUID = new ChannelUID(getThing().getUID(), channelId);
        updateThing(editThing().withoutChannel(channelUID).build());
        logger.debug("{}: detector reports no CO peak value, unlinked channel {} removed", getThing().getUID(),
                channelId);
    }

    private static boolean hasCoLevel(ThingTypeUID thingTypeUID) {
        return THING_TYPE_CO.equals(thingTypeUID) || THING_TYPE_COMBO.equals(thingTypeUID);
    }

    private static State onOff(int flag) {
        return flag < 0 ? UnDefType.UNDEF : OnOffType.from(flag > 0);
    }

    private static State ppm(int value) {
        return value < 0 ? UnDefType.UNDEF : new QuantityType<>(value, Units.PARTS_PER_MILLION);
    }

    private void clearMeasurements() {
        ThingTypeUID thingTypeUID = getThing().getThingTypeUID();
        List<String> channels = new ArrayList<>(List.of(CHANNEL_BATTERY, CHANNEL_LOW_BATTERY, CHANNEL_SIGNAL));
        String alarmChannel = alarmChannel();
        if (alarmChannel != null) {
            channels.add(alarmChannel);
        }
        if (hasCoLevel(thingTypeUID)) {
            channels.add(CHANNEL_CO_LEVEL);
            if (hasChannel(CHANNEL_CO_PEAK)) {
                channels.add(CHANNEL_CO_PEAK);
            }
        }
        if (THING_TYPE_DOOR.equals(thingTypeUID)) {
            channels.add(CHANNEL_CONTACT);
        } else if (THING_TYPE_MOTION.equals(thingTypeUID)) {
            channels.add(CHANNEL_MOTION);
        }
        for (String channel : channels) {
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
