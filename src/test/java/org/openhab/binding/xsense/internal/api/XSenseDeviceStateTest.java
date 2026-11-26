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

import java.util.List;

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
        var states = XSenseStationReport.reported(json("""
                {"state":{"reported":{"devs":{"A":{"online":"1","batInfo":1,"rfLevel":2,"status":{"alarmStatus":"1"}},
                "B":{"status":{"alarmStatus":0,"batInfo":"3"},"online":0}}},"desired":{"devs":{"C":{"online":1}}}}}
                """)).getAsJsonObject("devs");
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
    void desiredOnlyDocumentHasNoReportedState() {
        assertEquals(0, XSenseStationReport.reported(json("""
                {"state":{"desired":{"devs":{"A":{"online":1,"alarmStatus":1}}}}}
                """)).size());
    }

    @Test
    void decodesOnlineAndOnLineInAllDocumentedRepresentations() {
        var states = json("""
                {"A":{"online":1},"B":{"onLine":0},"C":{"onLine":"1"},"D":{"online":"false"},"E":{"onLine":true},
                "F":{"online":false},"G":{"status":{"onLine":"off"}},"H":{"online":"on"},"I":{"online":2},
                "J":{"online":"yes"},"K":{"online":1.5},"L":{"batInfo":3},"M":{"online":1,"onLine":0},
                "N":{"online":1,"status":{"onLine":"0"}},"O":{"online":1.0}}
                """);
        assertEquals(1, XSenseDeviceState.read(states, "A").online());
        assertEquals(0, XSenseDeviceState.read(states, "B").online());
        assertEquals(1, XSenseDeviceState.read(states, "C").online());
        assertEquals(0, XSenseDeviceState.read(states, "D").online());
        assertEquals(1, XSenseDeviceState.read(states, "E").online());
        assertEquals(0, XSenseDeviceState.read(states, "F").online());
        assertEquals(0, XSenseDeviceState.read(states, "G").online());
        assertEquals(1, XSenseDeviceState.read(states, "H").online());
        assertEquals(-1, XSenseDeviceState.read(states, "I").online());
        assertEquals(-1, XSenseDeviceState.read(states, "J").online());
        assertEquals(-1, XSenseDeviceState.read(states, "K").online());
        assertEquals(-1, XSenseDeviceState.read(states, "L").online());
        // Contradicting flags in one record: the explicit offline report wins
        assertEquals(0, XSenseDeviceState.read(states, "M").online());
        assertEquals(0, XSenseDeviceState.read(states, "N").online());
        assertEquals(1, XSenseDeviceState.read(states, "O").online());
    }

    @Test
    void offlineReportKeepsDecodedValuesForTheHandlerToDiscard() {
        XSenseDeviceState state = XSenseDeviceState.of(json("""
                {"onLine":0,"alarmStatus":0}
                """));
        assertEquals(0, state.online());
        assertEquals(0, state.alarm());
    }

    @Test
    void decodesBooleanAlarmStatus() {
        var states = json("""
                {"A":{"alarmStatus":true},"B":{"status":{"alarmStatus":"false"}},"C":{"isAlarm":true}}
                """);
        assertEquals(1, XSenseDeviceState.read(states, "A").alarm());
        assertEquals(0, XSenseDeviceState.read(states, "B").alarm());
        assertEquals(1, XSenseDeviceState.read(states, "C").alarm());
    }

    @Test
    void combineTakesComplementaryAndEqualValues() {
        var combined = XSenseDeviceState
                .combine(List.of(new XSenseDeviceState(1, -1, 3, -1), new XSenseDeviceState(-1, 1, 3, 2)));
        assertEquals(new XSenseDeviceState(1, 1, 3, 2), combined.state());
        assertEquals(List.of(), combined.conflicts());
        assertEquals(XSenseDeviceState.UNKNOWN, XSenseDeviceState.combine(List.of()).state());
    }

    @Test
    void combineResolvesConflictsPerField() {
        var combined = XSenseDeviceState.combine(
                List.of(new XSenseDeviceState(1, 0, 3, 2, 5, 9, 0, 1), new XSenseDeviceState(0, 2, 1, 3, 6, 10, 1, 0)));
        // online: offline wins, alarm: highest code wins, all others unknown
        assertEquals(new XSenseDeviceState(0, 2, -1, -1, -1, -1, -1, -1), combined.state());
        assertEquals(List.of("online", "alarmStatus", "batInfo", "rfLevel", "coPpm", "coPpmPeak", "isOpen", "isMoved"),
                combined.conflicts());
    }

    @Test
    void conflictFoundOnceIsNotResolvedByFurtherRecords() {
        XSenseDeviceState a = new XSenseDeviceState(1, 1, -1, 2);
        XSenseDeviceState b = new XSenseDeviceState(-1, 0, -1, 3);
        XSenseDeviceState c = new XSenseDeviceState(1, 0, 3, 2);
        for (List<XSenseDeviceState> order : List.of(List.of(a, b, c), List.of(a, c, b), List.of(b, a, c),
                List.of(b, c, a), List.of(c, a, b), List.of(c, b, a))) {
            var combined = XSenseDeviceState.combine(order);
            assertEquals(new XSenseDeviceState(1, 1, 3, -1), combined.state(), order.toString());
            assertEquals(List.of("alarmStatus", "rfLevel"), combined.conflicts(), order.toString());
        }
    }

    // === CO peak value ===

    @Test
    void decodesThePeakValueInAllForms() {
        var states = json("""
                {"STATUS_PEAK":{"online":1,"status":{"coPpm":3,"peak":{"coPpmPeak":17}}},
                 "RECORD_PEAK":{"coPpm":3,"peak":{"coPpmPeak":17}},
                 "STATUS_FLAT":{"status":{"coPpm":"3","coPpmPeak":"17"}},
                 "FLAT":{"coPpm":3,"coPpmPeak":17},
                 "ZERO":{"status":{"coPpm":0,"peak":{"coPpmPeak":0}}},
                 "NONE":{"online":1,"alarmStatus":1,"coPpm":3}}
                """);
        for (String key : List.of("STATUS_PEAK", "RECORD_PEAK", "STATUS_FLAT", "FLAT")) {
            assertEquals(3, XSenseDeviceState.read(states, key).coPpm(), key);
            assertEquals(17, XSenseDeviceState.read(states, key).coPeak(), key);
        }
        assertEquals(0, XSenseDeviceState.read(states, "ZERO").coPeak());
        assertEquals(0, XSenseDeviceState.read(states, "ZERO").coPpm());
        XSenseDeviceState none = XSenseDeviceState.read(states, "NONE");
        assertEquals(-1, none.coPeak());
        assertEquals(3, none.coPpm());
        assertEquals(1, none.alarm());
    }
}
