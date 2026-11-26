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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.xsense.internal.XSenseBindingConstants;
import org.openhab.binding.xsense.internal.api.FakeXSenseCloud;
import org.openhab.binding.xsense.internal.api.MutableClock;
import org.openhab.binding.xsense.internal.api.XSenseApiClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.internal.ThingImpl;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.State;

/**
 * Test fixture: the complete handler chain account → home → station → sensors against the scripted
 * {@link FakeXSenseCloud}. Sensor things are real thing objects, so channels can be added and
 * removed by the handlers; account, home and station are mocks.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseHandlerChain {

    static final ThingUID ACCOUNT_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_ACCOUNT, "account");
    static final ThingUID HOME_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_HOME, ACCOUNT_UID, "home");
    static final ThingUID STATION_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_STATION, HOME_UID, "station");

    final FakeXSenseCloud cloud;
    final Map<ChannelUID, State> states = new ConcurrentHashMap<>();
    final List<Map.Entry<ChannelUID, State>> stateHistory = new CopyOnWriteArrayList<>();
    final Map<ThingUID, List<ThingStatusInfo>> statuses = new ConcurrentHashMap<>();
    final Set<ChannelUID> linked = new HashSet<>();
    final Map<ThingUID, Thing> things = new ConcurrentHashMap<>();
    private final List<Thing> sensors = new CopyOnWriteArrayList<>();
    private final Bridge accountThing = mock(Bridge.class);
    private final XSenseAccountHandler account;

    XSenseHandlerChain(FakeXSenseCloud cloud, MutableClock clock, long retryUnitMillis) {
        this.cloud = cloud;
        Bridge homeThing = mockBridge(HOME_UID, XSenseBindingConstants.THING_TYPE_HOME, Map.of("houseId", HOUSE_ID),
                ACCOUNT_UID);
        Bridge stationThing = mockBridge(STATION_UID, XSenseBindingConstants.THING_TYPE_STATION,
                Map.of("stationSn", STATION_SN), HOME_UID);
        when(accountThing.getUID()).thenReturn(ACCOUNT_UID);
        when(accountThing.getThingTypeUID()).thenReturn(XSenseBindingConstants.THING_TYPE_ACCOUNT);
        when(accountThing.getConfiguration())
                .thenReturn(new Configuration(Map.of("email", "user@example.invalid", "password", "password")));
        when(accountThing.getThings()).thenReturn(List.of(homeThing));
        when(homeThing.getThings()).thenReturn(List.of(stationThing));
        when(stationThing.getThings()).thenAnswer(invocation -> List.copyOf(sensors));

        XSenseStationHandler station = new XSenseStationHandler(stationThing);
        initialize(station, stationThing);
        XSenseHomeHandler home = new XSenseHomeHandler(homeThing);
        initialize(home, homeThing);
        account = new XSenseAccountHandler(accountThing, new XSenseApiClient(cloud, clock), () -> 300, retryUnitMillis);
        account.setCallback(callback());
        when(accountThing.getHandler()).thenReturn(account);
    }

    private static Bridge mockBridge(ThingUID uid, ThingTypeUID type, Map<String, Object> config,
            @Nullable ThingUID bridgeUid) {
        Bridge bridge = mock(Bridge.class);
        when(bridge.getUID()).thenReturn(uid);
        when(bridge.getThingTypeUID()).thenReturn(type);
        when(bridge.getConfiguration()).thenReturn(new Configuration(config));
        when(bridge.getBridgeUID()).thenReturn(bridgeUid);
        return bridge;
    }

    private void initialize(BaseThingHandler handler, Thing thing) {
        handler.setCallback(callback());
        when(thing.getHandler()).thenReturn(handler);
        handler.initialize();
    }

    /** Adds a sensor thing as it exists in an installation, with the given channel ids. */
    XSenseSensorHandler addSensor(ThingTypeUID type, String id, String deviceSn, String... channels) {
        ThingUID uid = new ThingUID(type, STATION_UID, id);
        ThingBuilder builder = ThingBuilder.create(type, uid).withBridge(STATION_UID)
                .withConfiguration(new Configuration(Map.of("deviceSn", deviceSn)));
        for (String channel : channels) {
            builder.withChannel(ChannelBuilder.create(new ChannelUID(uid, channel)).build());
        }
        Thing thing = builder.build();
        XSenseSensorHandler handler = new XSenseSensorHandler(thing);
        ((ThingImpl) thing).setHandler(handler);
        things.put(uid, thing);
        sensors.add(thing);
        handler.setCallback(callback());
        handler.initialize();
        return handler;
    }

    private ThingHandlerCallback callback() {
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            ThingStatusInfo info = invocation.getArgument(1);
            statuses.computeIfAbsent(thing.getUID(), k -> new CopyOnWriteArrayList<>()).add(info);
            return null;
        }).when(callback).statusUpdated(any(), any());
        doAnswer(invocation -> {
            ChannelUID channel = invocation.getArgument(0);
            State state = invocation.getArgument(1);
            states.put(channel, state);
            stateHistory.add(Map.entry(channel, state));
            return null;
        }).when(callback).stateUpdated(any(), any());
        doAnswer(invocation -> {
            Thing thing = invocation.getArgument(0);
            things.put(thing.getUID(), thing);
            return null;
        }).when(callback).thingUpdated(any());
        when(callback.createChannelBuilder(any(), any())).thenAnswer(
                invocation -> ChannelBuilder.create((ChannelUID) invocation.getArgument(0), "Number:Dimensionless")
                        .withType((ChannelTypeUID) invocation.getArgument(1)));
        when(callback.isChannelLinked(any())).thenAnswer(invocation -> linked.contains(invocation.getArgument(0)));
        return callback;
    }

    XSenseAccountHandler account() {
        return account;
    }

    /** Connects as initialize() does, synchronously, and waits until the first poll is complete. */
    void connectAndWaitForFirstPoll() {
        account.connect();
        waitFor(() -> cloud.endpoints().contains("api:102007"), "first poll");
        account.pollNow();
    }

    void dispose() {
        account.dispose();
    }

    @Nullable
    State state(ThingUID thing, String channel) {
        return states.get(new ChannelUID(thing, channel));
    }

    ThingStatusInfo status(ThingUID thing) {
        List<ThingStatusInfo> history = statuses.get(thing);
        assertNotNull(history, thing.toString());
        return history.get(history.size() - 1);
    }

    List<ThingStatusInfo> history(ThingUID thing) {
        return new ArrayList<>(statuses.getOrDefault(thing, List.of()));
    }

    Thing thing(ThingUID uid) {
        Thing thing = things.get(uid);
        assertNotNull(thing, uid.toString());
        return thing;
    }

    /** Returns all states published on a channel, in order. */
    List<State> published(ThingUID thing, String channel) {
        ChannelUID uid = new ChannelUID(thing, channel);
        return stateHistory.stream().filter(entry -> entry.getKey().equals(uid)).map(Map.Entry::getValue).toList();
    }

    static void waitFor(BooleanSupplier condition, String description) {
        long deadline = System.currentTimeMillis() + 10000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + description);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }
}
