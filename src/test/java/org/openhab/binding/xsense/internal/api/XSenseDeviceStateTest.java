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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests decoding the reported smoke detector state.
 *
 * @author Andreas Rothstein - Initial contribution
 */
class XSenseDeviceStateTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void decodesOnlyTheSelectedDetectorAndReportedValues() throws XSenseApiException {
        var states = XSenseDeviceState.devices(json("""
                {"state":{"reported":{"devs":{"A":{"online":"1","batInfo":1,"rfLevel":2,"status":{"alarmStatus":"1"}},
                "B":{"status":{"alarmStatus":0,"batInfo":"3"},"online":0}}},"desired":{"devs":{"C":{"online":1}}}}}
                """));
        assertEquals(new XSenseDeviceState(1, 1, 1, 2), XSenseDeviceState.read(states, "A"));
        assertEquals(new XSenseDeviceState(0, 0, 3, -1), XSenseDeviceState.read(states, "B"));
        assertEquals(XSenseDeviceState.UNKNOWN, XSenseDeviceState.read(states, "C"));
    }

    @Test
    void handlesPartialAndMalformedReports() {
        var states = json("""
                {"A":{"batInfo":4,"rfLevel":[],"alarmStatus":"bad","isAlarm":0},
                "B":{"isAlarm":"1"},"C":{"alarmStatus":3,"status":{"rfLevel":"0"}}}
                """);
        assertEquals(XSenseDeviceState.UNKNOWN, XSenseDeviceState.read(states, "A"));
        assertEquals(1, XSenseDeviceState.read(states, "B").alarm());
        assertEquals(new XSenseDeviceState(-1, 3, -1, 0), XSenseDeviceState.read(states, "C"));
    }

    @Test
    void rejectsDesiredOnlyDocument() {
        assertThrows(XSenseApiException.class, () -> XSenseDeviceState.devices(json("""
                {"state":{"desired":{"devs":{}}}}
                """)));
    }
}
