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
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the assignment of station shadow records to the children of an SBS50. All serial numbers
 * and ids are anonymized test values.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseStationReportTest {

    private static final String SN_A = "XS01M-TEST-A";
    private static final String SN_B = "XS01M-TEST-B";
    private static final String ID_A = "00000001";
    private static final String ID_B = "00000002";

    private static JsonObject reported(String text) {
        return XSenseStationReport.reported(JsonParser.parseString(text).getAsJsonObject());
    }

    private static Device device(String serial, String id) {
        Device device = new Device();
        device.deviceSn = serial;
        device.deviceId = id;
        device.deviceType = "XS01-M";
        return device;
    }

    private static List<Device> twoDetectors() {
        return List.of(device(SN_A, ID_A), device(SN_B, ID_B));
    }

    @Test
    void assignsTheDevsMapKeyedBySerialNumber() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"devs":{
                  "XS01M-TEST-A":{"online":"1","batInfo":"3","rfLevel":"2","status":{"alarmStatus":"0"}},
                  "XS01M-TEST-B":{"online":"1","batInfo":"1","rfLevel":"3","status":{"alarmStatus":"1"}}}}}}
                """), twoDetectors());
        assertEquals(new XSenseDeviceState(1, 0, 3, 2), result.states().get(SN_A));
        assertEquals(new XSenseDeviceState(1, 1, 1, 3), result.states().get(SN_B));
        assertEquals(0, result.unknownRecords());
        assertEquals(0, result.conflictingRecords());
    }

    /**
     * Same case as test_parse_get_state_updates_child_status_when_apk_key_is_device_id of the Home
     * Assistant X-Sense project (commit 298f4d2): a flat record keyed by the deviceId.
     */
    @Test
    void assignsFlatRecordKeyedByDeviceId() {
        Device smoke = device("xs01m-sn", "00000007");
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"00000007":{"status":{"alarmStatus":"1","muteStatus":"0"}}}}}
                """), List.of(smoke));
        XSenseDeviceState state = result.states().get("xs01m-sn");
        assertNotNull(state);
        assertEquals(1, state.alarm());
        assertEquals(-1, state.online());
    }

    @Test
    void assignsFlatRecordKeyedBySerialNumber() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"XS01M-TEST-B":{"onLine":1,"alarmStatus":"0","batInfo":"2"}}}}
                """), twoDetectors());
        assertEquals(new XSenseDeviceState(1, 0, 2, -1), result.states().get(SN_B));
        assertFalse(result.states().containsKey(SN_A));
    }

    @Test
    void activeAlarmOfOneOfTwoDetectorsReachesOnlyThatDetector() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{
                  "devs":{"XS01M-TEST-A":{"online":1,"alarmStatus":"0"},"XS01M-TEST-B":{"online":1,"alarmStatus":"0"}},
                  "00000002":{"status":{"alarmStatus":"1"}}}}}
                """), twoDetectors());
        // B: devs record (alarm 0) and flat record (alarm 1) contradict each other: the alarm wins
        assertEquals(0, result.states().get(SN_A).alarm());
        assertEquals(1, result.states().get(SN_B).alarm());
        assertEquals(Map.of(SN_B, List.of("alarmStatus")), result.conflictingFields());

        result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{
                  "devs":{"XS01M-TEST-A":{"online":1,"alarmStatus":"0"},"XS01M-TEST-B":{"online":1}},
                  "00000002":{"status":{"alarmStatus":"1"}}}}}
                """), twoDetectors());
        assertEquals(new XSenseDeviceState(1, 0, -1, -1), result.states().get(SN_A));
        assertEquals(new XSenseDeviceState(1, 1, -1, -1), result.states().get(SN_B));
        assertTrue(result.conflictingFields().isEmpty());
    }

    @Test
    void ignoresUnknownDevicesAndStationFields() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"online":1,"alarmStatus":"1","wifi":{"rssi":-50},
                  "99999999":{"alarmStatus":"1"},
                  "devs":{"XS01M-TEST-X":{"online":1,"alarmStatus":"1"}}}}}
                """), twoDetectors());
        assertTrue(result.states().isEmpty());
        assertEquals(1, result.unknownRecords());
        assertEquals(0, result.conflictingRecords());
    }

    @Test
    void discardsRecordWhoseIdentifiersNameDifferentChildren() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"devs":{"XS01M-TEST-A":{"deviceId":"00000002","alarmStatus":"1"}},
                  "00000001":{"deviceSn":"XS01M-TEST-B","alarmStatus":"1"}}}}
                """), twoDetectors());
        assertTrue(result.states().isEmpty());
        assertEquals(2, result.conflictingRecords());
    }

    @Test
    void discardsRecordsForIdentifiersSharedByTwoChildren() {
        // Inconsistent inventory: the deviceId of B equals the serial number of A
        List<Device> children = List.of(device(SN_A, ID_A), device(SN_B, SN_A));
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"devs":{"XS01M-TEST-A":{"alarmStatus":"1"}},
                  "XS01M-TEST-B":{"alarmStatus":"0"}}}}
                """), children);
        assertFalse(result.states().containsKey(SN_A));
        assertEquals(0, result.states().get(SN_B).alarm());
        assertEquals(1, result.conflictingRecords());
    }

    @Test
    void acceptsAdditionalIdentifiersUnknownToTheInventory() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"devs":{"XS01M-TEST-A":{"sn":"SBS50-TEST","alarmStatus":"1"}}}}}
                """), twoDetectors());
        assertEquals(1, result.states().get(SN_A).alarm());
    }

    @Test
    void combinesComplementaryRecordsOfOneChild() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"devs":{"XS01M-TEST-A":{"batInfo":"3","rfLevel":"1"}},
                  "00000001":{"onLine":1,"status":{"alarmStatus":"0"}}}}}
                """), twoDetectors());
        assertEquals(new XSenseDeviceState(1, 0, 3, 1), result.states().get(SN_A));
    }

    @Test
    void reportsMissingValuesAsUnavailable() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"devs":{"XS01M-TEST-A":{},"XS01M-TEST-B":{"alarmStatus":null,"batInfo":"x"}}}}}
                """), twoDetectors());
        assertEquals(XSenseDeviceState.UNKNOWN, result.states().get(SN_A));
        assertEquals(XSenseDeviceState.UNKNOWN, result.states().get(SN_B));
    }

    @Test
    void neverUsesDesiredState() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"desired":{"devs":{"XS01M-TEST-A":{"online":1,"alarmStatus":"1"}},
                  "00000002":{"alarmStatus":"1"}}}}
                """), twoDetectors());
        assertTrue(result.states().isEmpty());
        assertEquals(0, result.unknownRecords() + result.conflictingRecords());
    }

    // === Value conflicts versus identity conflicts ===

    private static List<Device> singleDevice() {
        return List.of(device("sn", "idA"));
    }

    /**
     * Reproduction: one inventory device reported twice, by deviceSn in "devs" and by deviceId as
     * flat record. The identity is unique, only the signal strength differs.
     */
    @Test
    void uniqueIdentityKeepsTheAlarmAndOnlyTheContradictingFieldIsUnknown() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"devs":{"sn":{"online":1,"alarmStatus":1,"rfLevel":2}},
                  "idA":{"alarmStatus":1,"rfLevel":3}}}}
                """), singleDevice());
        XSenseDeviceState state = result.states().get("sn");
        assertNotNull(state);
        assertEquals(1, state.online());
        assertEquals(1, state.alarm());
        assertEquals(-1, state.signal());
        assertEquals(Map.of("sn", List.of("rfLevel")), result.conflictingFields());
        assertEquals(0, result.conflictingRecords());
    }

    @Test
    void contradictingAlarmReportsNeverYieldNoAlarm() {
        for (String reports : List.of("""
                {"devs":{"sn":{"online":1,"alarmStatus":0}},"idA":{"alarmStatus":1}}
                """, """
                {"idA":{"alarmStatus":1},"devs":{"sn":{"online":1,"alarmStatus":"0"}}}
                """, """
                {"devs":{"sn":{"online":1,"alarmStatus":1}},"idA":{"status":{"alarmStatus":"0"}}}
                """)) {
            var result = XSenseStationReport.resolve(reported("{\"state\":{\"reported\":" + reports + "}}"),
                    singleDevice());
            assertEquals(1, result.states().get("sn").alarm(), reports);
            assertEquals(List.of("alarmStatus"), result.conflictingFields().get("sn"), reports);
        }
    }

    @Test
    void offlineReportStillLocksTheDevice() {
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"devs":{"sn":{"online":1,"alarmStatus":1}},"idA":{"onLine":0}}}}
                """), singleDevice());
        assertEquals(0, result.states().get("sn").online());
        assertEquals(1, result.states().get("sn").alarm());
    }

    /**
     * Three partial records of one device (devs by serial, flat by deviceId, flat record naming the
     * device in a field) in all six orders: the signal conflict found in any order stays unknown.
     */
    @Test
    void conflictOfThreePartialRecordsIsIndependentOfTheirOrder() {
        String devs = "\"devs\":{\"sn\":{\"online\":1,\"rfLevel\":2}}";
        String byId = "\"idA\":{\"rfLevel\":3,\"alarmStatus\":1}";
        String byField = "\"rec\":{\"deviceId\":\"idA\",\"rfLevel\":2,\"batInfo\":3}";
        List<List<String>> orders = List.of(List.of(devs, byId, byField), List.of(devs, byField, byId),
                List.of(byId, devs, byField), List.of(byId, byField, devs), List.of(byField, devs, byId),
                List.of(byField, byId, devs));
        for (List<String> order : orders) {
            var result = XSenseStationReport.resolve(
                    reported("{\"state\":{\"reported\":{" + String.join(",", order) + "}}}"), singleDevice());
            assertEquals(new XSenseDeviceState(1, 1, 3, -1), result.states().get("sn"), order.toString());
            assertEquals(List.of("rfLevel"), result.conflictingFields().get("sn"), order.toString());
        }
    }

    // === Duplicate serial numbers in the inventory ===

    /**
     * Reproduction: two children share deviceSn "sn" (ids idA and idB), only idA reports an alarm.
     * A third child with a unique identity must still get its values.
     */
    @Test
    void sharedSerialNumberIsNotPublishedForAnyOfItsChildren() {
        List<Device> children = List.of(device("sn", "idA"), device("sn", "idB"), device("sn3", "idC"));
        var result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{"idA":{"online":1,"alarmStatus":1},
                  "devs":{"sn":{"online":1,"alarmStatus":1},"sn3":{"online":1,"alarmStatus":0,"batInfo":3}}}}}
                """), children);
        assertFalse(result.states().containsKey("sn"));
        assertEquals(Set.of("sn"), result.ambiguousSerials());
        assertEquals(new XSenseDeviceState(1, 0, 3, -1), result.states().get("sn3"));
        // The devs record keyed by the shared serial number cannot be assigned at all
        assertEquals(1, result.conflictingRecords());
    }

    @Test
    void detectsSharedSerialNumbers() {
        assertEquals(Set.of("sn"), XSenseStationReport
                .ambiguousSerials(List.of(device("sn", "a"), device("sn", "b"), device("other", "c"))));
        assertEquals(Set.of(), XSenseStationReport.ambiguousSerials(twoDetectors()));
        assertEquals(Set.of(), XSenseStationReport.ambiguousSerials(null));
    }
}
