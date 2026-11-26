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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

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
        // The peak value is reported in a "peak" object of the record or of its status object, or
        // directly as field; the first form present is used
        int coPeak = field(child(device, "peak"), null, "coPpmPeak", MAX_CO_PPM);
        if (coPeak < 0) {
            coPeak = field(child(status, "peak"), null, "coPpmPeak", MAX_CO_PPM);
        }
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

    /** Names of the record fields, in the order of {@link #values()}, used for diagnostics. */
    static final List<String> FIELD_NAMES = List.of("online", "alarmStatus", "batInfo", "rfLevel", "coPpm",
            "coPpmPeak", "isOpen", "isMoved");
    private static final int FIELD_ONLINE = 0;
    private static final int FIELD_ALARM = 1;

    /**
     * Result of {@link #combine(List)}: the combined state and the names of the fields whose values
     * contradicted each other.
     */
    public record Combined(XSenseDeviceState state, List<String> conflicts) {
    }

    /**
     * Combines all records reported for one device in one station report, field by field and
     * independent of their order. A field reported by only one record, or with the same value by
     * several records, is taken over. Contradicting values are resolved per field:
     * <ul>
     * <li>online: offline wins, so an explicitly offline device stays locked;</li>
     * <li>alarm: the highest alarm code wins, so contradicting alarm reports never yield "no
     * alarm";</li>
     * <li>all other fields: unknown (-1). A conflict found once cannot be resolved by further
     * records of the same report.</li>
     * </ul>
     */
    public static Combined combine(List<XSenseDeviceState> records) {
        int fieldCount = FIELD_NAMES.size();
        int[] combined = new int[fieldCount];
        List<String> conflicts = new ArrayList<>();
        for (int field = 0; field < fieldCount; field++) {
            Set<Integer> reported = new TreeSet<>();
            for (XSenseDeviceState record : records) {
                int value = record.values()[field];
                if (value >= 0) {
                    reported.add(value);
                }
            }
            if (reported.isEmpty()) {
                combined[field] = -1;
            } else if (reported.size() == 1) {
                combined[field] = reported.iterator().next();
            } else {
                conflicts.add(FIELD_NAMES.get(field));
                combined[field] = switch (field) {
                    case FIELD_ONLINE -> 0;
                    case FIELD_ALARM -> Collections.max(reported);
                    default -> -1;
                };
            }
        }
        return new Combined(new XSenseDeviceState(combined[0], combined[1], combined[2], combined[3], combined[4],
                combined[5], combined[6], combined[7]), List.copyOf(conflicts));
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
