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
package org.openhab.binding.xsense.internal.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.xsense.internal.XSenseBindingConstants;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Device;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Station;
import org.openhab.core.config.discovery.DiscoveryListener;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.thing.ThingUID;

/**
 * Discovery must not publish arbitrary data under a thing UID shared by several devices.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseDiscoveryDuplicateSerialTest {

    private static final ThingUID ACCOUNT_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_ACCOUNT, "account");
    private static final ThingUID HOME_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_HOME, ACCOUNT_UID, "home");
    private static final ThingUID STATION_UID = new ThingUID(XSenseBindingConstants.THING_TYPE_STATION, HOME_UID,
            "station");

    private static Device device(String serial, String id, String name) {
        Device device = new Device();
        device.deviceSn = serial;
        device.deviceId = id;
        device.deviceType = "XS01-M";
        device.deviceName = name;
        return device;
    }

    @Test
    void devicesSharingASerialNumberAreNotDiscovered() throws InterruptedException {
        List<DiscoveryResult> discovered = new CopyOnWriteArrayList<>();
        List<ThingUID> removed = new CopyOnWriteArrayList<>();
        DiscoveryListener listener = mock(DiscoveryListener.class);
        doAnswer(invocation -> discovered.add(invocation.getArgument(1))).when(listener).thingDiscovered(any(), any());
        doAnswer(invocation -> removed.add(invocation.getArgument(1))).when(listener).thingRemoved(any(), any());

        XSenseCloudDiscoveryService service = new XSenseCloudDiscoveryService();
        service.addDiscoveryListener(listener);
        Station station = new Station();
        station.stationSn = "station";
        station.devices = List.of(device("sn", "idA", "Kitchen"), device("sn", "idB", "Hall"),
                device("sn3", "idC", "Office"));
        service.publishDevices(station, STATION_UID);
        // The discovery service notifies its listeners asynchronously
        long deadline = System.currentTimeMillis() + 5000;
        while ((discovered.isEmpty() || removed.isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(200);

        ThingUID shared = new ThingUID(XSenseBindingConstants.THING_TYPE_SMOKE, STATION_UID, "sn");
        ThingUID unique = new ThingUID(XSenseBindingConstants.THING_TYPE_SMOKE, STATION_UID, "sn3");
        assertEquals(List.of(unique), discovered.stream().map(DiscoveryResult::getThingUID).toList());
        assertEquals("Office", discovered.get(0).getLabel());
        assertTrue(removed.contains(shared));
    }
}
