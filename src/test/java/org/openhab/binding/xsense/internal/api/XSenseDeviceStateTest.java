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
 * Tests decoding the reported detector and sensor states.
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
    void decodesCarbonMonoxideLevelsAndPeak() {
        var states = json("""
                {"CO":{"online":"1","batInfo":"3","rfLevel":"3","alarmStatus":"0","coPpm":"12",
                "peak":{"coPpmPeak":"45","time":"20260709010102"}},
                "FLAT":{"coPpm":7,"coPpmPeak":9},"BAD":{"coPpm":"-3","peak":{"coPpmPeak":"high"}}}
                """);
        XSenseDeviceState co = XSenseDeviceState.read(states, "CO");
        assertEquals(12, co.coPpm());
        assertEquals(45, co.coPeak());
        assertEquals(0, co.alarm());
        assertEquals(7, XSenseDeviceState.read(states, "FLAT").coPpm());
        assertEquals(9, XSenseDeviceState.read(states, "FLAT").coPeak());
        assertEquals(-1, XSenseDeviceState.read(states, "BAD").coPpm());
        assertEquals(-1, XSenseDeviceState.read(states, "BAD").coPeak());
    }

    @Test
    void decodesDoorContactInAllReportedForms() {
        var states = json("""
                {"A":{"isOpen":"1"},"B":{"a":"0"},"C":{"status":"open"},"D":{"status":{"doorStatus":"closed"}},
                "E":{"open":true},"F":{"batInfo":"2"}}
                """);
        assertEquals(1, XSenseDeviceState.read(states, "A").open());
        assertEquals(0, XSenseDeviceState.read(states, "B").open());
        assertEquals(1, XSenseDeviceState.read(states, "C").open());
        assertEquals(0, XSenseDeviceState.read(states, "D").open());
        assertEquals(1, XSenseDeviceState.read(states, "E").open());
        assertEquals(-1, XSenseDeviceState.read(states, "F").open());
    }

    @Test
    void decodesMotion() {
        var states = json("""
                {"M":{"isMoved":"1"},"N":{"status":{"isMoved":false}},"O":{"isMoved":"maybe"}}
                """);
        assertEquals(1, XSenseDeviceState.read(states, "M").motion());
        assertEquals(0, XSenseDeviceState.read(states, "N").motion());
        assertEquals(-1, XSenseDeviceState.read(states, "O").motion());
    }

    @Test
    void rejectsDesiredOnlyDocument() {
        assertThrows(XSenseApiException.class, () -> XSenseDeviceState.devices(json("""
                {"state":{"desired":{"devs":{}}}}
                """)));
    }
}
