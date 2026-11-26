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
package org.openhab.binding.xsense.internal;

import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.type.ChannelTypeUID;

/**
 * The {@link XSenseBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Markus Michels - Initial contribution
 */
@NonNullByDefault
public class XSenseBindingConstants {

    public static final String BINDING_ID = "xsense";

    // Bridge Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_HOME = new ThingTypeUID(BINDING_ID, "home");
    public static final ThingTypeUID THING_TYPE_STATION = new ThingTypeUID(BINDING_ID, "station");

    // Sensor Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_SMOKE = new ThingTypeUID(BINDING_ID, "smoke");
    public static final ThingTypeUID THING_TYPE_WATER = new ThingTypeUID(BINDING_ID, "water");
    public static final ThingTypeUID THING_TYPE_CO = new ThingTypeUID(BINDING_ID, "co");
    public static final ThingTypeUID THING_TYPE_COMBO = new ThingTypeUID(BINDING_ID, "combo");
    public static final ThingTypeUID THING_TYPE_HEAT = new ThingTypeUID(BINDING_ID, "heat");
    public static final ThingTypeUID THING_TYPE_DOOR = new ThingTypeUID(BINDING_ID, "door");
    public static final ThingTypeUID THING_TYPE_MOTION = new ThingTypeUID(BINDING_ID, "motion");

    public static final Set<ThingTypeUID> SENSOR_THING_TYPES = Set.of(THING_TYPE_SMOKE, THING_TYPE_WATER, THING_TYPE_CO,
            THING_TYPE_COMBO, THING_TYPE_HEAT, THING_TYPE_DOOR, THING_TYPE_MOTION);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_ACCOUNT, THING_TYPE_HOME,
            THING_TYPE_STATION, THING_TYPE_SMOKE, THING_TYPE_WATER, THING_TYPE_CO, THING_TYPE_COMBO, THING_TYPE_HEAT,
            THING_TYPE_DOOR, THING_TYPE_MOTION);

    /**
     * Maps the X-Sense device type (model code as reported by the cloud API) to the thing type. Only
     * devices connected to an SBS50 base station are listed. Models not listed here, e.g. stand-alone
     * Wi-Fi devices and thermo-hygrometers, are reported by the discovery service as unsupported.
     */
    private static final Map<String, ThingTypeUID> DEVICE_TYPE_TO_THING_TYPE = Map.ofEntries(
            Map.entry("XS01-M", THING_TYPE_SMOKE), //
            Map.entry("XS0B-MR", THING_TYPE_SMOKE), //
            Map.entry("XS0D-MR", THING_TYPE_SMOKE), //
            Map.entry("SD11-MR", THING_TYPE_SMOKE), //
            Map.entry("XP02S-MR", THING_TYPE_SMOKE), //
            Map.entry("SWS51", THING_TYPE_WATER), //
            Map.entry("SWS0A", THING_TYPE_WATER), //
            Map.entry("XC01-M", THING_TYPE_CO), //
            Map.entry("XC0C-MR", THING_TYPE_CO), //
            Map.entry("XC0M-MR", THING_TYPE_CO), //
            Map.entry("SC01-MR", THING_TYPE_COMBO), //
            Map.entry("SC07-MR", THING_TYPE_COMBO), //
            Map.entry("XP0A-MR", THING_TYPE_COMBO), //
            Map.entry("XP0H-MR", THING_TYPE_COMBO), //
            Map.entry("XP0P-MR", THING_TYPE_COMBO), //
            Map.entry("XH02-M", THING_TYPE_HEAT), //
            Map.entry("SDS0A", THING_TYPE_DOOR), //
            Map.entry("SMS0A", THING_TYPE_MOTION));

    // Device type of the SBS50 base station as reported in the station "category" field
    public static final String STATION_TYPE_SBS50 = "SBS50";

    // Thing configuration parameters
    public static final String CONFIG_DEVICE_SN = "deviceSn";
    public static final String CONFIG_HOUSE_ID = "houseId";
    public static final String CONFIG_STATION_SN = "stationSn";

    // Thing properties
    public static final String PROPERTY_HOUSE_ID = "houseId";
    public static final String PROPERTY_HOUSE_NAME = "houseName";
    public static final String PROPERTY_ROOM_NAME = "roomName";

    // Channels of the sensor things
    public static final String CHANNEL_ALARM_SMOKE = "alarm#smoke";
    public static final String CHANNEL_ALARM_WATER = "alarm#water";
    public static final String CHANNEL_ALARM_CO = "alarm#co";
    public static final String CHANNEL_ALARM_COMBO = "alarm#alarm";
    public static final String CHANNEL_ALARM_HEAT = "alarm#heat";
    public static final String CHANNEL_CO_LEVEL = "measurement#co";
    public static final String CHANNEL_CO_PEAK = "measurement#coPeak";
    // Added to a thing only when its detector reports a peak value, see XSenseSensorHandler
    public static final ChannelTypeUID CHANNEL_TYPE_CO_PEAK = new ChannelTypeUID(BINDING_ID, "co-peak");
    public static final String CHANNEL_CONTACT = "state#contact";
    public static final String CHANNEL_MOTION = "state#motion";
    public static final String CHANNEL_BATTERY = "device#battery";
    public static final String CHANNEL_LOW_BATTERY = "device#lowBattery";
    public static final String CHANNEL_SIGNAL = "device#signal";

    /**
     * Returns the thing type for an X-Sense model code or null for unsupported models.
     */
    public static @Nullable ThingTypeUID thingTypeForDeviceType(String deviceType) {
        return DEVICE_TYPE_TO_THING_TYPE.get(deviceType);
    }
}
