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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.xsense.internal.XSenseBindingConstants;
import org.openhab.binding.xsense.internal.api.XSenseDeviceState;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Tests that the optional CO peak channel only exists for detectors that report a peak value, and
 * that a linked channel of an existing installation is never removed.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseCoPeakChannelTest {

    private static final String CO = "measurement#co";
    private static final String CO_PEAK = "measurement#coPeak";

    private final Map<String, State> published = new HashMap<>();
    private final Set<String> linked = new HashSet<>();
    private @Nullable Thing current;

    private XSenseSensorHandler handler(ThingTypeUID type, boolean withPeakChannel) {
        ThingUID uid = new ThingUID(type, "station", "detector");
        ThingBuilder builder = ThingBuilder.create(type, uid)
                .withChannel(ChannelBuilder.create(new ChannelUID(uid, CO), "Number:Dimensionless").build());
        if (withPeakChannel) {
            builder.withChannel(ChannelBuilder.create(new ChannelUID(uid, CO_PEAK), "Number:Dimensionless")
                    .withType(XSenseBindingConstants.CHANNEL_TYPE_CO_PEAK).build());
        }
        Thing thing = builder.build();
        current = thing;
        XSenseSensorHandler handler = new XSenseSensorHandler(thing);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        doAnswer(invocation -> {
            ChannelUID channel = invocation.getArgument(0);
            published.put(channel.getId(), invocation.getArgument(1));
            return null;
        }).when(callback).stateUpdated(any(), any());
        doAnswer(invocation -> {
            current = invocation.getArgument(0);
            return null;
        }).when(callback).thingUpdated(any());
        when(callback.createChannelBuilder(any(), any())).thenAnswer(
                invocation -> ChannelBuilder.create((ChannelUID) invocation.getArgument(0), "Number:Dimensionless")
                        .withType((ChannelTypeUID) invocation.getArgument(1)));
        when(callback.isChannelLinked(any()))
                .thenAnswer(invocation -> linked.contains(((ChannelUID) invocation.getArgument(0)).getId()));
        handler.setCallback(callback);
        return handler;
    }

    private static Device report(int coPpm, int coPeak) {
        Device device = new Device();
        device.reported = new XSenseDeviceState(1, 0, 3, 3, coPpm, coPeak, -1, -1);
        return device;
    }

    private @Nullable Channel peakChannel() {
        Thing thing = current;
        assertNotNull(thing);
        return thing.getChannel(CO_PEAK);
    }

    @Test
    void detectorWithoutPeakValueGetsNoPeakChannel() {
        XSenseSensorHandler handler = handler(XSenseBindingConstants.THING_TYPE_CO, false);
        handler.updateFromInventory(report(0, -1));
        handler.updateFromInventory(report(3, -1));
        assertNull(peakChannel());
        assertFalse(published.containsKey(CO_PEAK));
        assertEquals(new QuantityType<>(3, Units.PARTS_PER_MILLION), published.get(CO));
    }

    @Test
    void unlinkedPeakChannelOfAnEarlierVersionIsRemoved() {
        XSenseSensorHandler handler = handler(XSenseBindingConstants.THING_TYPE_CO, true);
        handler.updateFromInventory(report(0, -1));
        assertNull(peakChannel());
        assertNotNull(current.getChannel(CO));
    }

    @Test
    void linkedPeakChannelIsKeptAndUndefined() {
        linked.add(CO_PEAK);
        XSenseSensorHandler handler = handler(XSenseBindingConstants.THING_TYPE_CO, true);
        handler.updateFromInventory(report(0, -1));
        assertNotNull(peakChannel());
        assertEquals(UnDefType.UNDEF, published.get(CO_PEAK));
    }

    @Test
    void peakChannelIsAddedWhenReportedAndKeptAfterwards() {
        XSenseSensorHandler handler = handler(XSenseBindingConstants.THING_TYPE_COMBO, false);
        handler.updateFromInventory(report(10, 45));
        Channel channel = peakChannel();
        assertNotNull(channel);
        assertEquals(XSenseBindingConstants.CHANNEL_TYPE_CO_PEAK, channel.getChannelTypeUID());
        assertEquals(new QuantityType<>(45, Units.PARTS_PER_MILLION), published.get(CO_PEAK));

        // A single report without the value neither removes the channel nor keeps the old value
        handler.updateFromInventory(report(10, -1));
        assertNotNull(peakChannel());
        assertEquals(UnDefType.UNDEF, published.get(CO_PEAK));
    }
}
