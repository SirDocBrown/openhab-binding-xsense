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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Assigns the records of an SBS50 station shadow to the children of that station.
 *
 * Only the reported state ("state.reported") is evaluated; desired values are never used. The
 * station reports its children in two forms, both documented by the Home Assistant X-Sense project:
 * <ul>
 * <li>a "devs" map, keyed by the device serial number (deviceSn) or app id (deviceId)</li>
 * <li>flat records directly under "state.reported", keyed by deviceSn or deviceId</li>
 * </ul>
 * A record may additionally name its device in the fields {@link #RECORD_ID_FIELDS}.
 *
 * Assignment rules, chosen so that an alarm never reaches the item of another detector:
 * <ol>
 * <li>Every identifier of a record (its key and the identifier fields) is looked up in the
 * inventory of the station, by deviceSn and by deviceId.</li>
 * <li>A record is assigned only when all identifiers found in the inventory name the same single
 * child. Identifiers naming different children, or an identifier shared by several children of the
 * inventory, make the record conflicting; it is discarded. Identifiers unknown to the inventory do
 * not prevent the assignment, as records may carry further ids, e.g. of the station.</li>
 * <li>Records whose identifiers name no child are ignored (unknown devices); in the flat form only
 * objects whose key or identifier fields name a child are device records at all.</li>
 * <li>Several records for the same child are combined field by field. If they report different
 * values for the same field, the child gets no state from this report.</li>
 * </ol>
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public final class XSenseStationReport {

    /** Fields a record may use to name its device, see python-xsense. */
    static final List<String> RECORD_ID_FIELDS = List.of("deviceSN", "deviceSn", "_deviceSN", "_deviceSn",
            "devSerialNumber", "serialNumber", "sn", "deviceId");
    private static final String DEVS = "devs";

    /**
     * Result of the assignment.
     *
     * @param states reported state per child, keyed by deviceSn
     * @param conflictingDevices deviceSn of the children whose records contradict each other
     * @param unknownRecords number of records for devices that are not part of the station
     * @param conflictingRecords number of records discarded because of contradicting identifiers
     */
    public record Result(Map<String, XSenseDeviceState> states, Set<String> conflictingDevices, int unknownRecords,
            int conflictingRecords) {
    }

    private XSenseStationReport() {
    }

    /**
     * Returns "state.reported" of a shadow document, or an empty object when the shadow has no
     * reported state (e.g. only a desired state).
     */
    public static JsonObject reported(@Nullable JsonObject document) {
        JsonObject state = child(document, "state");
        JsonObject reported = child(state, "reported");
        return reported != null ? reported : new JsonObject();
    }

    /**
     * Assigns the reported records to the children of the station, see the class documentation.
     */
    public static Result resolve(JsonObject reported, List<Device> children) {
        Map<String, List<Device>> byIdentifier = new HashMap<>();
        for (Device child : children) {
            for (String identifier : new String[] { child.deviceSn, child.deviceId }) {
                if (identifier != null && !identifier.isBlank()) {
                    List<Device> named = byIdentifier.get(identifier);
                    if (named == null) {
                        named = new ArrayList<>();
                        byIdentifier.put(identifier, named);
                    }
                    if (!named.contains(child)) {
                        named.add(child);
                    }
                }
            }
        }

        Map<Device, List<XSenseDeviceState>> records = new IdentityHashMap<>();
        int unknown = 0;
        int conflicting = 0;
        List<Map.Entry<String, JsonObject>> candidates = new ArrayList<>();
        JsonObject devs = child(reported, DEVS);
        if (devs != null) {
            for (Map.Entry<String, JsonElement> entry : devs.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    candidates.add(Map.entry(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }
        }
        for (Map.Entry<String, JsonElement> entry : reported.entrySet()) {
            if (!DEVS.equals(entry.getKey()) && entry.getValue().isJsonObject()) {
                JsonObject record = entry.getValue().getAsJsonObject();
                // Other objects of the station, e.g. its own status, are no device records
                if (identifiers(entry.getKey(), record).stream().anyMatch(byIdentifier::containsKey)) {
                    candidates.add(Map.entry(entry.getKey(), record));
                }
            }
        }

        for (Map.Entry<String, JsonObject> candidate : candidates) {
            Set<String> identifiers = identifiers(candidate.getKey(), candidate.getValue());
            Set<Device> named = new LinkedHashSet<>();
            boolean ambiguous = false;
            for (String identifier : identifiers) {
                List<Device> matches = byIdentifier.get(identifier);
                if (matches == null) {
                    continue;
                }
                if (matches.size() > 1) {
                    ambiguous = true;
                } else {
                    named.add(matches.get(0));
                }
            }
            if (named.isEmpty() && !ambiguous) {
                unknown++;
            } else if (ambiguous || named.size() > 1) {
                conflicting++;
            } else {
                Device device = named.iterator().next();
                List<XSenseDeviceState> deviceRecords = records.get(device);
                if (deviceRecords == null) {
                    deviceRecords = new ArrayList<>();
                    records.put(device, deviceRecords);
                }
                deviceRecords.add(XSenseDeviceState.of(candidate.getValue()));
            }
        }

        Map<String, XSenseDeviceState> states = new HashMap<>();
        Set<String> conflictingDevices = new LinkedHashSet<>();
        for (Map.Entry<Device, List<XSenseDeviceState>> entry : records.entrySet()) {
            String serial = entry.getKey().deviceSn;
            if (serial == null || serial.isBlank()) {
                continue;
            }
            @Nullable
            XSenseDeviceState combined = XSenseDeviceState.UNKNOWN;
            for (XSenseDeviceState state : entry.getValue()) {
                combined = XSenseDeviceState.merge(combined, state);
                if (combined == null) {
                    break;
                }
            }
            if (combined == null) {
                conflictingDevices.add(serial);
            } else {
                states.put(serial, combined);
            }
        }
        return new Result(Map.copyOf(states), Set.copyOf(conflictingDevices), unknown, conflicting);
    }

    private static Set<String> identifiers(String key, JsonObject record) {
        Set<String> identifiers = new LinkedHashSet<>();
        if (!key.isBlank()) {
            identifiers.add(key);
        }
        for (String field : RECORD_ID_FIELDS) {
            JsonElement value = record.get(field);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                identifiers.add(value.getAsString());
            }
        }
        return identifiers;
    }

    private static @Nullable JsonObject child(@Nullable JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
}
