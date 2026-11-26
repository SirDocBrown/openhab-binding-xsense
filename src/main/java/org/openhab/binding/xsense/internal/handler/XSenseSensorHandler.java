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

import static org.openhab.binding.xsense.internal.XSenseBindingConstants.CHANNEL_PATH;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.xsense.internal.XSenseBindingConstants;
import org.openhab.binding.xsense.internal.api.XSenseDeviceState;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;
import org.openhab.binding.xsense.internal.config.XSenseDeviceConfiguration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * The {@link XSenseSensorHandler} represents a sensor attached to an X-Sense base station (smoke,
 * CO, heat, water leak or thermo-hygrometer). In the current phase it maintains the thing status
 * and properties from inventory and smoke detector measurements from the station shadow.
 *
 * @author Markus Michels - Initial contribution
 */
@NonNullByDefault
public class XSenseSensorHandler extends BaseThingHandler {

    // Written in initialize(), read via getDeviceSn() from the station handler's update path
    private volatile XSenseDeviceConfiguration config = new XSenseDeviceConfiguration();
    private final XSenseChannelState channelState = new XSenseChannelState();
    private final XSenseChannelLabeler labeler = new XSenseChannelLabeler();

    public XSenseSensorHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        config = getConfigAs(XSenseDeviceConfiguration.class);
        if (config.deviceSn.isBlank()) {
            clearMeasurements();
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.config-error-device-sn");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        requestRefresh();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            channelState.invalidate(channelUID.getId());
            requestRefresh();
        }
    }

    /**
     * Requests an inventory refresh via the station handler. Called on initialization too, since
     * otherwise a newly added sensor thing would stay UNKNOWN until the next unrelated poll cycle
     * happens to fire.
     */
    private void requestRefresh() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof XSenseStationHandler stationHandler) {
            stationHandler.requestRefresh();
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() != ThingStatus.ONLINE) {
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
     * @param stationPath the hierarchy path of the station this sensor belongs to
     */
    public void updateFromInventory(@Nullable Device device, XSensePath stationPath) {
        if (device == null) {
            clearMeasurements();
            updateStatus(ThingStatusDetail.GONE, "@text/offline.device-not-found");
            return;
        }
        XSensePath path = stationPath.withDevice(config.deviceSn, device.deviceName);
        updateProperties(device, path);
        applyChannelLabels(device.deviceName);
        if (XSenseBindingConstants.THING_TYPE_SMOKE.equals(getThing().getThingTypeUID())) {
            XSenseDeviceState report = device.reported;
            if (report.online() == 0 || XSenseDeviceState.UNKNOWN.equals(report)) {
                clearMeasurements();
                updateStatus(report.online() == 0 ? ThingStatus.OFFLINE : ThingStatus.UNKNOWN);
            } else {
                updateChannel("alarm#smoke", report.alarm() < 0 ? UnDefType.UNDEF : OnOffType.from(report.alarm() > 0));
                updateChannel("device#battery", report.battery() < 0 ? UnDefType.UNDEF
                        : new DecimalType(Math.round(report.battery() * 100f / 3)));
                updateChannel("device#lowBattery",
                        report.battery() < 0 ? UnDefType.UNDEF : OnOffType.from(report.battery() <= 1));
                updateChannel("device#signal",
                        report.signal() < 0 ? UnDefType.UNDEF : new DecimalType(report.signal()));
                updateStatus(report.online() == 1 ? ThingStatus.ONLINE : ThingStatus.UNKNOWN);
            }
        } else {
            updateStatus(ThingStatus.ONLINE);
        }
        updateChannel(CHANNEL_PATH, new StringType(path.toJson()));
    }

    private void clearMeasurements() {
        if (XSenseBindingConstants.THING_TYPE_SMOKE.equals(getThing().getThingTypeUID())) {
            for (String channel : List.of("alarm#smoke", "device#battery", "device#lowBattery", "device#signal")) {
                updateChannel(channel, UnDefType.UNDEF);
            }
        }
    }

    private void applyChannelLabels(@Nullable String name) {
        List<Channel> channels = labeler.relabel(getThing(), name);
        if (channels != null) {
            updateThing(editThing().withChannels(channels).build());
        }
    }

    private void updateProperties(Device device, XSensePath path) {
        Map<String, String> properties = new HashMap<>();
        String deviceType = device.deviceType;
        if (deviceType != null) {
            properties.put(Thing.PROPERTY_MODEL_ID, deviceType);
        }
        String roomName = device.roomName;
        if (roomName != null) {
            properties.put(XSenseBindingConstants.PROPERTY_ROOM_NAME, roomName);
        }
        properties.put(Thing.PROPERTY_SERIAL_NUMBER, config.deviceSn);
        properties.put(Thing.PROPERTY_VENDOR, "X-Sense");
        properties.put(XSenseBindingConstants.PROPERTY_UNIQUE_ID, path.getUniqueId());
        updateProperties(properties);
    }

    private void updateChannel(String channelId, State state) {
        if (channelState.update(channelId, state)) {
            updateState(channelId, state);
        }
    }
}
