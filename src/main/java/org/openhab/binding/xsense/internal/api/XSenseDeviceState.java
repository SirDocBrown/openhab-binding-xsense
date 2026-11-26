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
 * Reported status of one SBS50 child. A negative level indicates an unavailable field.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public record XSenseDeviceState(int online, int alarm, int battery, int signal) {
    public static final XSenseDeviceState UNKNOWN = new XSenseDeviceState(-1, -1, -1, -1);

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
        return new XSenseDeviceState(field(device, status, "online", 1), alarm, field(device, status, "batInfo", 3),
                field(device, status, "rfLevel", 3));
    }

    private static int field(@Nullable JsonObject device, @Nullable JsonObject status, String key, int maximum) {
        return level(status != null && status.has(key) ? status : device, key, maximum);
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
