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
package org.openhab.binding.xsense.internal.api;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reported status of one SBS50 child. A negative value indicates an unavailable field.
 *
 * The field names follow the station shadow as documented by the python-xsense and Home Assistant
 * X-Sense projects. Values may be reported directly in the device object or in its nested
 * "status" object.
 *
 * @param online 1 when the device is connected to the base station, 0 when not
 * @param alarm alarm status code, 0 means no alarm
 * @param battery battery level 0..3
 * @param signal RF signal level 0..3
 * @param coPpm current carbon monoxide concentration in ppm (CO and combination detectors)
 * @param coPeak peak carbon monoxide concentration in ppm (CO and combination detectors)
 * @param open 1 when open, 0 when closed (door/window contacts)
 * @param motion 1 when motion is detected, 0 otherwise (motion detectors)
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public record XSenseDeviceState(int online, int alarm, int battery, int signal, int coPpm, int coPeak, int open,
        int motion) {
    public static final XSenseDeviceState UNKNOWN = new XSenseDeviceState(-1, -1, -1, -1);

    // Upper bound of plausible CO readings; X-Sense detectors measure up to 1000 ppm
    private static final int MAX_CO_PPM = 10000;
    // Keys the door contacts use for their open state, see the property mapping of python-xsense
    private static final String[] OPEN_KEYS = { "isOpen", "open", "door", "doorStatus", "a" };

    /**
     * Creates a state with the fields shared by all detectors.
     */
    public XSenseDeviceState(int online, int alarm, int battery, int signal) {
        this(online, alarm, battery, signal, -1, -1, -1, -1);
    }

    static JsonObject devices(@Nullable JsonObject document) throws XSenseApiException {
        JsonObject devices = child(child(child(document, "state"), "reported"), "devs");
        if (devices == null) {
            throw new XSenseApiException("Station shadow has no reported device map");
        }
        return devices;
    }

    public static XSenseDeviceState read(JsonObject devices, String serial) {
        JsonObject device = child(devices, serial);
        JsonObject status = child(device, "status");
        int alarm = field(device, status, "alarmStatus", Integer.MAX_VALUE);
        if ((device == null || !device.has("alarmStatus")) && (status == null || !status.has("alarmStatus"))) {
            alarm = field(device, status, "isAlarm", 1);
        }
        int coPeak = field(child(device, "peak"), null, "coPpmPeak", MAX_CO_PPM);
        if (coPeak < 0) {
            coPeak = field(device, status, "coPpmPeak", MAX_CO_PPM);
        }
        return new XSenseDeviceState(field(device, status, "online", 1), alarm, field(device, status, "batInfo", 3),
                field(device, status, "rfLevel", 3), field(device, status, "coPpm", MAX_CO_PPM), coPeak,
                openState(device), flag(device, status, "isMoved"));
    }

    /**
     * Returns the open state of a door contact. Besides the keys in {@link #OPEN_KEYS} the contact may
     * report the state as a plain "status" value instead of a nested status object.
     */
    private static int openState(@Nullable JsonObject device) {
        JsonObject status = child(device, "status");
        for (String key : OPEN_KEYS) {
            int value = flag(device, status, key);
            if (value >= 0) {
                return value;
            }
        }
        JsonElement plainStatus = device == null ? null : device.get("status");
        return plainStatus != null && plainStatus.isJsonPrimitive() ? toFlag(plainStatus.getAsString()) : -1;
    }

    private static int field(@Nullable JsonObject device, @Nullable JsonObject status, String key, int maximum) {
        return level(status != null && status.has(key) ? status : device, key, maximum);
    }

    private static int flag(@Nullable JsonObject device, @Nullable JsonObject status, String key) {
        JsonObject source = status != null && status.has(key) ? status : device;
        JsonElement value = source == null ? null : source.get(key);
        return value != null && value.isJsonPrimitive() ? toFlag(value.getAsString()) : -1;
    }

    private static int toFlag(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "1", "true", "on", "open", "opened" -> 1;
            case "0", "false", "off", "closed", "close", "shut" -> 0;
            default -> -1;
        };
    }

    private static @Nullable JsonObject child(@Nullable JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static int level(@Nullable JsonObject object, String key, int maximum) {
        JsonElement value = object == null ? null : object.get(key);
        if (value != null && value.isJsonPrimitive()) {
            String raw = value.getAsString();
            if (raw.matches("[0-9]{1,9}")) {
                int number = Integer.parseInt(raw);
                return number <= maximum ? number : -1;
            }
        }
        return -1;
    }
}
