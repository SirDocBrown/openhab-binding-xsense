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

import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reported status of one SBS50 child. A negative value indicates an unavailable field.
 * The assignment of reported records to the children of a station is done by {@link XSenseStationReport}.
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
    // Both spellings are used by the station shadow and the inventory, see python-xsense
    private static final String[] ONLINE_KEYS = { "online", "onLine" };

    /**
     * Creates a state with the fields shared by all detectors.
     */
    public XSenseDeviceState(int online, int alarm, int battery, int signal) {
        this(online, alarm, battery, signal, -1, -1, -1, -1);
    }

    /**
     * Decodes the record of the device stored under the given key, e.g. in the "devs" map.
     */
    public static XSenseDeviceState read(JsonObject devices, String key) {
        return of(child(devices, key));
    }

    /**
     * Decodes one reported device record. Missing or malformed values are reported as -1.
     */
    public static XSenseDeviceState of(@Nullable JsonObject device) {
        JsonObject status = child(device, "status");
        int alarm = alarmField(device, status, "alarmStatus");
        if ((device == null || !device.has("alarmStatus")) && (status == null || !status.has("alarmStatus"))) {
            alarm = alarmField(device, status, "isAlarm");
        }
        int coPeak = field(child(device, "peak"), null, "coPpmPeak", MAX_CO_PPM);
        if (coPeak < 0) {
            coPeak = field(device, status, "coPpmPeak", MAX_CO_PPM);
        }
        return new XSenseDeviceState(online(device, status), alarm, field(device, status, "batInfo", 3),
                field(device, status, "rfLevel", 3), field(device, status, "coPpm", MAX_CO_PPM), coPeak,
                openState(device), flag(device, status, "isMoved"));
    }

    /**
     * Returns the connection state from "online" or "onLine", in the device record or its status
     * object. Accepted are booleans, the numbers 1 and 0 and the strings 1/true/on and 0/false/off.
     * An explicit offline report takes precedence over any online flag in the same record, so a
     * record with contradicting flags never shows a detector as connected.
     *
     * @return 1 online, 0 offline, -1 when not reported
     */
    static int online(@Nullable JsonObject device, @Nullable JsonObject status) {
        int result = -1;
        for (JsonObject source : new JsonObject[] { device, status }) {
            if (source == null) {
                continue;
            }
            for (String key : ONLINE_KEYS) {
                int value = connectionFlag(source.get(key));
                if (value == 0) {
                    return 0;
                }
                if (value == 1) {
                    result = 1;
                }
            }
        }
        return result;
    }

    /**
     * Decodes one online flag, see {@link #online(JsonObject, JsonObject)}.
     */
    public static int connectionFlag(@Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return -1;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean() ? 1 : 0;
        }
        if (primitive.isNumber()) {
            try {
                int number = primitive.getAsBigDecimal().intValueExact();
                return number == 0 || number == 1 ? number : -1;
            } catch (ArithmeticException | NumberFormatException e) {
                return -1;
            }
        }
        return switch (primitive.getAsString().trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "on" -> 1;
            case "0", "false", "off" -> 0;
            default -> -1;
        };
    }

    /**
     * Combines two records reported for the same device. Values present in only one record are
     * taken over; when both records report a different value for the same field, the reports
     * contradict each other and null is returned.
     */
    public static @Nullable XSenseDeviceState merge(XSenseDeviceState a, XSenseDeviceState b) {
        int[] first = a.values();
        int[] second = b.values();
        int[] merged = new int[first.length];
        for (int i = 0; i < first.length; i++) {
            if (first[i] >= 0 && second[i] >= 0 && first[i] != second[i]) {
                return null;
            }
            merged[i] = first[i] >= 0 ? first[i] : second[i];
        }
        return new XSenseDeviceState(merged[0], merged[1], merged[2], merged[3], merged[4], merged[5], merged[6],
                merged[7]);
    }

    private int[] values() {
        return new int[] { online, alarm, battery, signal, coPpm, coPeak, open, motion };
    }

    private static int alarmField(@Nullable JsonObject device, @Nullable JsonObject status, String key) {
        JsonObject source = status != null && status.has(key) ? status : device;
        JsonElement value = source == null ? null : source.get(key);
        if (value != null && value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean() ? 1 : 0;
            }
            String raw = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(raw) || "false".equals(raw)) {
                return "true".equals(raw) ? 1 : 0;
            }
        }
        return level(source, key, Integer.MAX_VALUE);
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
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
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
