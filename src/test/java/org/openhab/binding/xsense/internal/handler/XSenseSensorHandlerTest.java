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
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.xsense.internal.XSenseBindingConstants;
import org.openhab.binding.xsense.internal.api.XSenseDeviceState;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Tests the detector channels consumed by notification rules on successive polls.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseSensorHandlerTest {

    private final Map<String, State> published = new HashMap<>();
    private @Nullable ThingStatus status;

    private XSenseSensorHandler createHandler(ThingTypeUID thingTypeUID) {
        Thing thing = mock(Thing.class);
        when(thing.getThingTypeUID()).thenReturn(thingTypeUID);
        return new XSenseSensorHandler(thing) {
            @Override
            protected void updateState(String id, State state) {
                published.put(id, state);
            }

            @Override
            protected void updateStatus(ThingStatus status) {
                XSenseSensorHandlerTest.this.status = status;
            }

            @Override
            protected void updateStatus(ThingStatusDetail detail, @Nullable String description) {
                XSenseSensorHandlerTest.this.status = ThingStatus.OFFLINE;
            }

            @Override
            protected void updateProperties(@Nullable Map<String, String> properties) {
                // No framework callback in this test.
            }
        };
    }

    @Test
    void smokeDetectorPublishesAlarmBatteryAndRecovery() {
        XSenseSensorHandler handler = createHandler(XSenseBindingConstants.THING_TYPE_SMOKE);
        Device device = new Device();

        device.reported = new XSenseDeviceState(1, 1, 1, 3);
        handler.updateFromInventory(device);
        assertEquals(OnOffType.ON, published.get("alarm#smoke"));
        assertEquals(OnOffType.ON, published.get("device#lowBattery"));
        assertEquals(new DecimalType(33), published.get("device#battery"));
        assertEquals(new DecimalType(3), published.get("device#signal"));
        assertEquals(ThingStatus.ONLINE, status);

        device.reported = new XSenseDeviceState(0, 0, 3, 2);
        handler.updateFromInventory(device);
        assertEquals(UnDefType.UNDEF, published.get("alarm#smoke"));
        assertEquals(UnDefType.UNDEF, published.get("device#lowBattery"));
        assertEquals(ThingStatus.OFFLINE, status);

        device.reported = new XSenseDeviceState(1, 0, 2, -1);
        handler.updateFromInventory(device);
        assertEquals(OnOffType.OFF, published.get("alarm#smoke"));
        assertEquals(OnOffType.OFF, published.get("device#lowBattery"));
        assertEquals(new DecimalType(67), published.get("device#battery"));
        assertEquals(UnDefType.UNDEF, published.get("device#signal"));
        assertEquals(ThingStatus.ONLINE, status);
    }

    @Test
    void waterDetectorPublishesLeakAlarm() {
        XSenseSensorHandler handler = createHandler(XSenseBindingConstants.THING_TYPE_WATER);
        Device device = new Device();

        device.reported = new XSenseDeviceState(1, 1, 3, 2);
        handler.updateFromInventory(device);
        assertEquals(OnOffType.ON, published.get("alarm#water"));
        assertEquals(OnOffType.OFF, published.get("device#lowBattery"));
        assertEquals(new DecimalType(100), published.get("device#battery"));
        assertFalse(published.containsKey("alarm#smoke"));
    }

    @Test
    void missingReportClearsMeasurements() {
        XSenseSensorHandler handler = createHandler(XSenseBindingConstants.THING_TYPE_SMOKE);
        Device device = new Device();

        device.reported = new XSenseDeviceState(1, 1, 3, 3);
        handler.updateFromInventory(device);
        device.reported = XSenseDeviceState.UNKNOWN;
        handler.updateFromInventory(device);
        assertEquals(UnDefType.UNDEF, published.get("alarm#smoke"));
        assertEquals(UnDefType.UNDEF, published.get("device#battery"));
        assertEquals(ThingStatus.UNKNOWN, status);
    }

    @Test
    void removedDeviceGoesOffline() {
        XSenseSensorHandler handler = createHandler(XSenseBindingConstants.THING_TYPE_SMOKE);
        handler.updateFromInventory(null);
        assertEquals(ThingStatus.OFFLINE, status);
        assertEquals(UnDefType.UNDEF, published.get("alarm#smoke"));
    }
}
