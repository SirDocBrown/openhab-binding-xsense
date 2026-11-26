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
package org.openhab.binding.xsense.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.xsense.internal.api.FakeXSenseCloud.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.xsense.internal.XSenseBindingConstants;
import org.openhab.binding.xsense.internal.api.FakeXSenseCloud;
import org.openhab.binding.xsense.internal.api.MutableClock;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.types.UnDefType;

/**
 * Simulated station reports through the complete handler chain for the models of the installation
 * (XS01-M, XC01-M): combined records, contradicting alarms, duplicate serial numbers and the
 * optional CO peak value. All reports come from the scripted fake cloud with anonymized ids.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseAlarmChainTest {

    private static final String[] SENSOR_CHANNELS = { "device#battery", "device#lowBattery", "device#signal" };
    private static final ThingUID SMOKE = new ThingUID(XSenseBindingConstants.THING_TYPE_SMOKE,
            XSenseHandlerChain.STATION_UID, "smoke");
    private static final ThingUID CO = new ThingUID(XSenseBindingConstants.THING_TYPE_CO,
            XSenseHandlerChain.STATION_UID, "co");

    private final FakeXSenseCloud cloud = new FakeXSenseCloud();
    private @Nullable XSenseHandlerChain chain;

    @AfterEach
    void tearDown() {
        XSenseHandlerChain current = chain;
        if (current != null) {
            current.dispose();
        }
    }

    private static String[] channels(String... specific) {
        String[] all = new String[SENSOR_CHANNELS.length + specific.length];
        System.arraycopy(SENSOR_CHANNELS, 0, all, 0, SENSOR_CHANNELS.length);
        System.arraycopy(specific, 0, all, SENSOR_CHANNELS.length, specific.length);
        return all;
    }

    /** Smoke detector "sm-sn" (XS01-M) and CO detector "co-sn" (XC01-M) on one SBS50. */
    private XSenseHandlerChain installation(String reported, String... extraCoChannels) {
        cloud.set("api:103007", stationsWithModels("1", "sm-sn", "sm-id", "XS01-M", "co-sn", "co-id", "XC01-M"));
        cloud.set("shadow:2nd_mainpage", shadow(reported));
        XSenseHandlerChain installation = new XSenseHandlerChain(cloud, new MutableClock(), 1000);
        installation.addSensor(XSenseBindingConstants.THING_TYPE_SMOKE, "smoke", "sm-sn", channels("alarm#smoke"));
        String[] co = new String[extraCoChannels.length + 2];
        co[0] = "alarm#co";
        co[1] = "measurement#co";
        System.arraycopy(extraCoChannels, 0, co, 2, extraCoChannels.length);
        installation.addSensor(XSenseBindingConstants.THING_TYPE_CO, "co", "co-sn", channels(co));
        chain = installation;
        installation.connectAndWaitForFirstPoll();
        return installation;
    }

    // === 1. Combined records with a unique identity ===

    @Test
    void alarmOfTwoAgreeingRecordsIsPublishedAndOnlyTheSignalIsUnknown() {
        XSenseHandlerChain installation = installation("""
                {"devs":{"sm-sn":{"online":1,"alarmStatus":1,"rfLevel":2},
                         "co-sn":{"online":1,"alarmStatus":1,"rfLevel":2,"coPpm":120}},
                 "sm-id":{"alarmStatus":1,"rfLevel":3},
                 "co-id":{"alarmStatus":1,"rfLevel":3}}
                """);
        assertEquals(OnOffType.ON, installation.state(SMOKE, "alarm#smoke"));
        assertEquals(OnOffType.ON, installation.state(CO, "alarm#co"));
        assertEquals(UnDefType.UNDEF, installation.state(SMOKE, "device#signal"));
        assertEquals(UnDefType.UNDEF, installation.state(CO, "device#signal"));
        assertEquals(new QuantityType<>(120, Units.PARTS_PER_MILLION), installation.state(CO, "measurement#co"));
        assertEquals(ThingStatus.ONLINE, installation.status(SMOKE).getStatus());
        assertEquals(ThingStatus.ONLINE, installation.status(CO).getStatus());
    }

    @Test
    void contradictingAlarmRecordsPublishAnAlarmAndNeverOff() {
        XSenseHandlerChain installation = installation("""
                {"devs":{"sm-sn":{"online":1,"alarmStatus":"0"},"co-sn":{"online":1,"alarmStatus":0}},
                 "sm-id":{"status":{"alarmStatus":"1"}},"co-id":{"alarmStatus":1}}
                """);
        // Every poll publishes the alarm, never OFF
        for (ThingUID detector : List.of(SMOKE, CO)) {
            String channel = detector.equals(SMOKE) ? "alarm#smoke" : "alarm#co";
            List<?> published = installation.published(detector, channel);
            assertFalse(published.isEmpty());
            assertTrue(published.stream().allMatch(OnOffType.ON::equals), published.toString());
        }
    }

    @Test
    void offlineRecordStillLocksTheDetector() {
        XSenseHandlerChain installation = installation("""
                {"devs":{"sm-sn":{"online":1,"alarmStatus":1},"co-sn":{"online":1,"alarmStatus":0}},
                 "sm-id":{"onLine":0}}
                """);
        assertEquals(ThingStatus.OFFLINE, installation.status(SMOKE).getStatus());
        assertEquals(UnDefType.UNDEF, installation.state(SMOKE, "alarm#smoke"));
        assertEquals(OnOffType.OFF, installation.state(CO, "alarm#co"));
    }

    // === 2. Duplicate serial numbers ===

    @Test
    void sharedSerialNumberPublishesNoValuesWhileAUniqueDeviceStillDoes() {
        cloud.set("api:103007",
                stationsWithModels("1", "sn", "idA", "XS01-M", "sn", "idB", "XS01-M", "sn3", "idC", "XS01-M"));
        cloud.set("shadow:2nd_mainpage", shadow("""
                {"idA":{"online":1,"alarmStatus":1},
                 "devs":{"sn":{"online":1,"alarmStatus":1},"sn3":{"online":1,"alarmStatus":0,"batInfo":"3"}}}
                """));
        XSenseHandlerChain installation = new XSenseHandlerChain(cloud, new MutableClock(), 1000);
        chain = installation;
        ThingUID shared = new ThingUID(XSenseBindingConstants.THING_TYPE_SMOKE, XSenseHandlerChain.STATION_UID,
                "shared");
        ThingUID unique = new ThingUID(XSenseBindingConstants.THING_TYPE_SMOKE, XSenseHandlerChain.STATION_UID,
                "unique");
        installation.addSensor(XSenseBindingConstants.THING_TYPE_SMOKE, "shared", "sn", channels("alarm#smoke"));
        installation.addSensor(XSenseBindingConstants.THING_TYPE_SMOKE, "unique", "sn3", channels("alarm#smoke"));
        installation.connectAndWaitForFirstPoll();

        assertFalse(installation.published(shared, "alarm#smoke").contains(OnOffType.ON));
        assertEquals(UnDefType.UNDEF, installation.state(shared, "alarm#smoke"));
        assertEquals(ThingStatus.OFFLINE, installation.status(shared).getStatus());
        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, installation.status(shared).getStatusDetail());

        assertEquals(OnOffType.OFF, installation.state(unique, "alarm#smoke"));
        assertEquals(OnOffType.OFF, installation.state(unique, "device#lowBattery"));
        assertEquals(ThingStatus.ONLINE, installation.status(unique).getStatus());
    }

    // === 4. Optional CO peak value ===

    @Test
    void peakValueFromStatusPeakIsPublishedOnAnAddedChannel() {
        XSenseHandlerChain installation = installation("""
                {"devs":{"sm-sn":{"online":1,"alarmStatus":0},
                  "co-sn":{"online":1,"status":{"alarmStatus":0,"coPpm":3,"peak":{"coPpmPeak":17}}}}}
                """);
        assertEquals(new QuantityType<>(3, Units.PARTS_PER_MILLION), installation.state(CO, "measurement#co"));
        assertEquals(new QuantityType<>(17, Units.PARTS_PER_MILLION), installation.state(CO, "measurement#coPeak"));
        assertNotNull(installation.thing(CO).getChannel("measurement#coPeak"));

        cloud.set("shadow:2nd_mainpage", shadow("""
                {"devs":{"co-sn":{"online":1,"status":{"alarmStatus":0,"coPpm":4,"peak":{"coPpmPeak":0}}}}}
                """));
        installation.account().pollNow();
        assertEquals(new QuantityType<>(0, Units.PARTS_PER_MILLION), installation.state(CO, "measurement#coPeak"));
    }

    @Test
    void detectorWithoutPeakValueStillPublishesConcentrationAndAlarm() {
        XSenseHandlerChain installation = installation("""
                {"devs":{"co-sn":{"online":1,"alarmStatus":1,"coPpm":3}}}
                """);
        assertEquals(new QuantityType<>(3, Units.PARTS_PER_MILLION), installation.state(CO, "measurement#co"));
        assertEquals(OnOffType.ON, installation.state(CO, "alarm#co"));
        assertNull(installation.thing(CO).getChannel("measurement#coPeak"));
        assertNull(installation.state(CO, "measurement#coPeak"));
    }

    @Test
    void linkedPeakChannelOfAnInstallationIsKept() {
        cloud.set("api:103007", stationsWithModels("1", "co-sn", "co-id", "XC01-M"));
        cloud.set("shadow:2nd_mainpage", shadow("""
                {"devs":{"co-sn":{"online":1,"alarmStatus":0,"coPpm":3}}}
                """));
        XSenseHandlerChain installation = new XSenseHandlerChain(cloud, new MutableClock(), 1000);
        chain = installation;
        installation.linked.add(new org.openhab.core.thing.ChannelUID(CO, "measurement#coPeak"));
        installation.addSensor(XSenseBindingConstants.THING_TYPE_CO, "co", "co-sn",
                channels("alarm#co", "measurement#co", "measurement#coPeak"));
        installation.connectAndWaitForFirstPoll();
        assertNotNull(installation.thing(CO).getChannel("measurement#coPeak"));
        assertEquals(UnDefType.UNDEF, installation.state(CO, "measurement#coPeak"));
        assertEquals(new QuantityType<>(3, Units.PARTS_PER_MILLION), installation.state(CO, "measurement#co"));
        assertEquals(OnOffType.OFF, installation.state(CO, "alarm#co"));
    }
}
