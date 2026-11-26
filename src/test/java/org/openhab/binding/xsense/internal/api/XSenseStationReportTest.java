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
        // B: devs record (alarm 0) and flat record (alarm 1) contradict each other -> no state
        assertEquals(0, result.states().get(SN_A).alarm());
        assertFalse(result.states().containsKey(SN_B));
        assertEquals(java.util.Set.of(SN_B), result.conflictingDevices());

        result = XSenseStationReport.resolve(reported("""
                {"state":{"reported":{
                  "devs":{"XS01M-TEST-A":{"online":1,"alarmStatus":"0"},"XS01M-TEST-B":{"online":1}},
                  "00000002":{"status":{"alarmStatus":"1"}}}}}
                """), twoDetectors());
        assertEquals(new XSenseDeviceState(1, 0, -1, -1), result.states().get(SN_A));
        assertEquals(new XSenseDeviceState(1, 1, -1, -1), result.states().get(SN_B));
        assertTrue(result.conflictingDevices().isEmpty());
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
}
