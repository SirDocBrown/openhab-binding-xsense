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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.openhab.binding.xsense.internal.api.FakeXSenseCloud.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.xsense.internal.XSenseBindingConstants;
import org.openhab.binding.xsense.internal.api.FakeXSenseCloud;
import org.openhab.binding.xsense.internal.api.MutableClock;
import org.openhab.binding.xsense.internal.api.XSenseApiClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Runs the complete handler chain account → home → station → two XS01-M detectors against the
 * scripted {@link FakeXSenseCloud}. The thing UIDs and channel ids are the ones used by existing
 * installations and their notification rules (alarm#smoke, device#lowBattery).
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseCloudPollingTest {

    private static final String SN_A = "XS01M-TEST-A";
    private static final String SN_B = "XS01M-TEST-B";
    private static final String ID_A = "00000001";
    private static final String ID_B = "00000002";

    private static final ThingUID ACCOUNT_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_ACCOUNT, "account");
    private static final ThingUID HOME_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_HOME, ACCOUNT_UID, "home");
    private static final ThingUID STATION_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_STATION, HOME_UID,
            "station");
    private static final ThingUID SMOKE_A_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_SMOKE, STATION_UID,
            "smokeA");
    private static final ThingUID SMOKE_B_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_SMOKE, STATION_UID,
            "smokeB");

    private final FakeXSenseCloud cloud = new FakeXSenseCloud();
    private final MutableClock clock = new MutableClock();
    private final Map<ChannelUID, State> states = new ConcurrentHashMap<>();
    private final Map<ThingUID, List<ThingStatusInfo>> statuses = new ConcurrentHashMap<>();

    private @Nullable XSenseAccountHandler account;

    private static String devs(String alarmA, String alarmB) {
        return """
                {"devs":{"%s":{"online":1,"alarmStatus":"%s","batInfo":"3","rfLevel":"3"},
                  "%s":{"online":1,"alarmStatus":"%s","batInfo":"1","rfLevel":"2"}}}
                """.formatted(SN_A, alarmA, SN_B, alarmB);
    }

    private ThingHandlerCallback callback() {
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingStatusInfo info = invocation.getArgument(1);
            statuses.computeIfAbsent(thing.getUID(), k -> new ArrayList<>()).add(info);
            return null;
        }).when(callback).statusUpdated(any(), any());
        doAnswer(invocation -> {
            ChannelUID channel = invocation.getArgument(0);
            State state = invocation.getArgument(1);
            states.put(channel, state);
            return null;
        }).when(callback).stateUpdated(any(), any());
        return callback;
    }

    private static Thing thing(Thing thing, ThingUID uid, ThingTypeUID type, Map<String, Object> config,
            @Nullable ThingUID bridgeUid) {
        when(thing.getUID()).thenReturn(uid);
        when(thing.getThingTypeUID()).thenReturn(type);
        when(thing.getConfiguration()).thenReturn(new Configuration(config));
        when(thing.getBridgeUID()).thenReturn(bridgeUid);
        return thing;
    }

    private void initialize(BaseThingHandler handler, Thing thing) {
        handler.setCallback(callback());
        when(thing.getHandler()).thenReturn(handler);
        handler.initialize();
    }

    @BeforeEach
    void setUp() {
        cloud.set("api:103007", stations("1", SN_A, ID_A, SN_B, ID_B));
        cloud.set("shadow:2nd_mainpage", shadow(devs("0", "0")));

        Bridge accountThing = (Bridge) thing(mock(Bridge.class), ACCOUNT_UID, XSenseBindingConstants.THING_TYPE_ACCOUNT,
                Map.of("email", "user@example.invalid", "password", "password"), null);
        Bridge homeThing = (Bridge) thing(mock(Bridge.class), HOME_UID, XSenseBindingConstants.THING_TYPE_HOME,
                Map.of("houseId", HOUSE_ID), ACCOUNT_UID);
        Bridge stationThing = (Bridge) thing(mock(Bridge.class), STATION_UID, XSenseBindingConstants.THING_TYPE_STATION,
                Map.of("stationSn", STATION_SN), HOME_UID);
        Thing smokeA = thing(mock(Thing.class), SMOKE_A_UID, XSenseBindingConstants.THING_TYPE_SMOKE,
                Map.of("deviceSn", SN_A), STATION_UID);
        Thing smokeB = thing(mock(Thing.class), SMOKE_B_UID, XSenseBindingConstants.THING_TYPE_SMOKE,
                Map.of("deviceSn", SN_B), STATION_UID);
        when(accountThing.getThings()).thenReturn(List.of(homeThing));
        when(homeThing.getThings()).thenReturn(List.of(stationThing));
        when(stationThing.getThings()).thenReturn(List.of(smokeA, smokeB));

        initialize(new XSenseSensorHandler(smokeA), smokeA);
        initialize(new XSenseSensorHandler(smokeB), smokeB);
        initialize(new XSenseStationHandler(stationThing), stationThing);
        initialize(new XSenseHomeHandler(homeThing), homeThing);

        XSenseAccountHandler handler = new XSenseAccountHandler(accountThing, new XSenseApiClient(cloud, clock),
                () -> 300);
        handler.setCallback(callback());
        when(accountThing.getHandler()).thenReturn(handler);
        account = handler;
        // initialize() would run connect() asynchronously; call the same method synchronously
        handler.connect();
        // connect() schedules the first poll without delay; wait until it has run so that it cannot
        // interfere with the steps of a test (the next scheduled poll follows after 300 s)
        long deadline = System.currentTimeMillis() + 10000;
        while (cloud.endpoints().stream().filter("api:102007"::equals).count() < 1
                && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        // pollNow() synchronizes on the handler, i.e. it also waits until the scheduled poll is complete
        handler.pollNow();
    }

    @AfterEach
    void tearDown() {
        XSenseAccountHandler handler = account;
        if (handler != null) {
            handler.dispose();
        }
    }

    private XSenseAccountHandler account() {
        XSenseAccountHandler handler = account;
        assertNotNull(handler);
        return handler;
    }

    private @Nullable State state(ThingUID thing, String channel) {
        return states.get(new ChannelUID(thing, channel));
    }

    private ThingStatusInfo status(ThingUID thing) {
        List<ThingStatusInfo> history = statuses.get(thing);
        assertNotNull(history, thing.toString());
        return history.get(history.size() - 1);
    }

    @Test
    void publishesBothDetectorsOnTheExistingChannels() {
        assertEquals(ThingStatus.ONLINE, status(ACCOUNT_UID).getStatus());
        assertEquals(ThingStatus.ONLINE, status(STATION_UID).getStatus());
        assertEquals(ThingStatus.ONLINE, status(SMOKE_A_UID).getStatus());
        assertEquals(OnOffType.OFF, state(SMOKE_A_UID, "alarm#smoke"));
        assertEquals(OnOffType.OFF, state(SMOKE_B_UID, "alarm#smoke"));
        assertEquals(OnOffType.OFF, state(SMOKE_A_UID, "device#lowBattery"));
        assertEquals(OnOffType.ON, state(SMOKE_B_UID, "device#lowBattery"));
    }

    @Test
    void flatAlarmRecordKeyedByDeviceIdReachesOnlyTheRightDetector() {
        cloud.set("shadow:2nd_mainpage", shadow("""
                {"devs":{"%s":{"online":1,"alarmStatus":"0"},"%s":{"online":1}},
                 "%s":{"status":{"alarmStatus":"1","muteStatus":"0"}}}
                """.formatted(SN_A, SN_B, ID_B)));
        account().pollNow();
        assertEquals(OnOffType.OFF, state(SMOKE_A_UID, "alarm#smoke"));
        assertEquals(OnOffType.ON, state(SMOKE_B_UID, "alarm#smoke"));

        // Flat record keyed by the serial number of A, B cleared again
        cloud.set("shadow:2nd_mainpage", shadow("""
                {"%s":{"onLine":"1","alarmStatus":"1"},"%s":{"onLine":"1","alarmStatus":"0"}}
                """.formatted(SN_A, ID_B)));
        account().pollNow();
        assertEquals(OnOffType.ON, state(SMOKE_A_UID, "alarm#smoke"));
        assertEquals(OnOffType.OFF, state(SMOKE_B_UID, "alarm#smoke"));
    }

    @Test
    void explicitlyOfflineDetectorIsOfflineAndReturnsToOnline() {
        cloud.set("shadow:2nd_mainpage", shadow("""
                {"devs":{"%s":{"onLine":0,"alarmStatus":0},
                  "%s":{"online":1,"alarmStatus":"0","batInfo":"3","rfLevel":"3"}}}
                """.formatted(SN_A, SN_B)));
        account().pollNow();
        assertEquals(ThingStatus.OFFLINE, status(SMOKE_A_UID).getStatus());
        assertEquals(ThingStatusDetail.COMMUNICATION_ERROR, status(SMOKE_A_UID).getStatusDetail());
        assertEquals(UnDefType.UNDEF, state(SMOKE_A_UID, "alarm#smoke"));
        assertEquals(UnDefType.UNDEF, state(SMOKE_A_UID, "device#lowBattery"));
        assertEquals(UnDefType.UNDEF, state(SMOKE_A_UID, "device#battery"));
        assertEquals(UnDefType.UNDEF, state(SMOKE_A_UID, "device#signal"));
        assertEquals(ThingStatus.ONLINE, status(SMOKE_B_UID).getStatus());

        cloud.set("shadow:2nd_mainpage", shadow(devs("0", "0")));
        account().pollNow();
        assertEquals(ThingStatus.ONLINE, status(SMOKE_A_UID).getStatus());
        assertEquals(OnOffType.OFF, state(SMOKE_A_UID, "alarm#smoke"));
        assertEquals(OnOffType.OFF, state(SMOKE_A_UID, "device#lowBattery"));
    }

    @Test
    void explicitlyOfflineDetectorIsOfflineDespiteOldValuesAndRecovers() {
        cloud.set("shadow:2nd_mainpage", shadow("""
                {"devs":{"%s":{"onLine":"0","alarmStatus":"1","batInfo":"3","rfLevel":"3"},
                  "%s":{"online":1,"alarmStatus":"0","batInfo":"3","rfLevel":"3"}}}
                """.formatted(SN_A, SN_B)));
        account().pollNow();
        assertEquals(ThingStatus.OFFLINE, status(SMOKE_A_UID).getStatus());
        assertEquals(ThingStatusDetail.COMMUNICATION_ERROR, status(SMOKE_A_UID).getStatusDetail());
        assertEquals(UnDefType.UNDEF, state(SMOKE_A_UID, "alarm#smoke"));
        assertEquals(UnDefType.UNDEF, state(SMOKE_A_UID, "device#lowBattery"));
        assertEquals(ThingStatus.ONLINE, status(SMOKE_B_UID).getStatus());

        cloud.set("shadow:2nd_mainpage", shadow(devs("0", "0")));
        account().pollNow();
        assertEquals(ThingStatus.ONLINE, status(SMOKE_A_UID).getStatus());
        assertEquals(OnOffType.OFF, state(SMOKE_A_UID, "alarm#smoke"));
    }

    @Test
    void offlineStationKeepsItsDetectorsOfflineAcrossInventoryPolls() {
        cloud.set("api:103007", stations("0", SN_A, ID_A, SN_B, ID_B));
        statuses.clear();
        cloud.clearCalls();
        account().pollNow();
        account().pollNow();

        assertEquals(ThingStatus.OFFLINE, status(STATION_UID).getStatus());
        for (ThingUID detector : List.of(SMOKE_A_UID, SMOKE_B_UID)) {
            List<ThingStatusInfo> history = statuses.get(detector);
            assertNotNull(history);
            assertFalse(history.isEmpty());
            for (ThingStatusInfo info : history) {
                assertEquals(ThingStatus.OFFLINE, info.getStatus(), detector + " " + history);
                assertEquals(ThingStatusDetail.BRIDGE_OFFLINE, info.getStatusDetail());
            }
            assertEquals(UnDefType.UNDEF, state(detector, "alarm#smoke"));
        }
        assertFalse(cloud.endpoints().stream().anyMatch(endpoint -> endpoint.startsWith("shadow:")));

        cloud.set("api:103007", stations("1", SN_A, ID_A, SN_B, ID_B));
        account().pollNow();
        assertEquals(ThingStatus.ONLINE, status(SMOKE_A_UID).getStatus());
        assertEquals(OnOffType.OFF, state(SMOKE_A_UID, "alarm#smoke"));
    }

    @Test
    void failedTokenRenewalKeepsPollingAndPublishesCurrentValuesAfterRecovery() {
        assertTrue(account().isPolling());
        clock.advance(Duration.ofHours(1));
        cloud.then("cognito:InitiateAuth/refresh", ok(authResult("access-invalid", null, "\"soon\"")));
        cloud.then("cognito:RespondToAuthChallenge", ok(rawAuthResult("{}", "\"refresh-x\"", "3600")));
        cloud.set("shadow:2nd_mainpage", shadow(devs("0", "1")));

        account().pollNow();
        assertEquals(ThingStatus.OFFLINE, status(ACCOUNT_UID).getStatus());
        assertEquals(ThingStatusDetail.COMMUNICATION_ERROR, status(ACCOUNT_UID).getStatusDetail());
        assertTrue(account().isPolling(), "the periodic poll must survive a failed token renewal");
        assertEquals(OnOffType.OFF, state(SMOKE_B_UID, "alarm#smoke"));

        account().pollNow();
        assertEquals(ThingStatus.ONLINE, status(ACCOUNT_UID).getStatus());
        assertEquals(OnOffType.ON, state(SMOKE_B_UID, "alarm#smoke"));
        assertEquals(OnOffType.OFF, state(SMOKE_A_UID, "alarm#smoke"));
    }

    @Test
    void unexpectedRuntimeErrorDoesNotStopPolling() {
        cloud.then("api:102007", CRASH);
        cloud.set("shadow:2nd_mainpage", shadow(devs("1", "0")));
        account().pollNow();
        assertEquals(ThingStatus.OFFLINE, status(ACCOUNT_UID).getStatus());
        assertTrue(account().isPolling());

        account().pollNow();
        assertEquals(ThingStatus.ONLINE, status(ACCOUNT_UID).getStatus());
        assertEquals(OnOffType.ON, state(SMOKE_A_UID, "alarm#smoke"));
    }
}
