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

import org.junit.jupiter.api.Test;
import org.openhab.binding.xsense.internal.XSenseBindingConstants;
import org.openhab.binding.xsense.internal.api.XSenseDeviceState;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Tests the smoke channels consumed by notification rules on successive polls.
 *
 * @author Andreas Rothstein - Initial contribution
 */
class XSenseSensorHandlerTest {
    @Test
    void publishesAlarmBatteryAndRecovery() {
        Thing thing = mock(Thing.class);
        when(thing.getThingTypeUID()).thenReturn(XSenseBindingConstants.THING_TYPE_SMOKE);
        Map<String, State> published = new HashMap<>();
        var handler = new XSenseSensorHandler(thing) {
            @Override
            protected void updateState(String id, State state) {
                published.put(id, state);
            }

            @Override
            protected void updateStatus(ThingStatus status) {
                // Observe channel publications independently of the framework callback.
            }

            @Override
            protected void updateProperties(Map<String, String> properties) {
                // No framework callback in this test.
            }
        };
        Device device = new Device();
        var path = XSensePath.account("account").withHome("house", "house").withStation("station", "station");
        device.reported = new XSenseDeviceState(1, 1, 1, 3);
        handler.updateFromInventory(device, path);
        assertEquals(OnOffType.ON, published.get("alarm#smoke"));
        assertEquals(OnOffType.ON, published.get("device#lowBattery"));
        assertEquals(new DecimalType(33), published.get("device#battery"));
        assertEquals(new DecimalType(3), published.get("device#signal"));

        device.reported = new XSenseDeviceState(0, 0, 3, 2);
        handler.updateFromInventory(device, path);
        assertEquals(UnDefType.UNDEF, published.get("alarm#smoke"));
        assertEquals(UnDefType.UNDEF, published.get("device#lowBattery"));

        device.reported = new XSenseDeviceState(1, 0, 2, -1);
        handler.updateFromInventory(device, path);
        assertEquals(OnOffType.OFF, published.get("alarm#smoke"));
        assertEquals(OnOffType.OFF, published.get("device#lowBattery"));
        assertEquals(new DecimalType(67), published.get("device#battery"));
        assertEquals(UnDefType.UNDEF, published.get("device#signal"));
    }
}
