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
import static org.openhab.binding.xsense.internal.XSenseBindingConstants.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.xsense.internal.api.XSenseDeviceState;

/**
 * Tests the payload-free diagnosis written to the debug log for every reported device.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseAccountHandlerDiagnosticsTest {

    @Test
    void describesResultAndMissingFieldsOnly() {
        assertEquals("complete",
                XSenseAccountHandler.describe(new XSenseDeviceState(1, 0, 3, 2), List.of(), false, THING_TYPE_SMOKE));
        assertEquals("missing rfLevel",
                XSenseAccountHandler.describe(new XSenseDeviceState(1, 1, 3, -1), List.of(), false, THING_TYPE_SMOKE));
        assertEquals("missing online, alarmStatus, batInfo, rfLevel",
                XSenseAccountHandler.describe(XSenseDeviceState.UNKNOWN, List.of(), false, THING_TYPE_SMOKE));
        assertEquals("reported offline",
                XSenseAccountHandler.describe(new XSenseDeviceState(0, 1, 3, 3), List.of(), false, THING_TYPE_SMOKE));
        assertEquals("no report", XSenseAccountHandler.describe(null, List.of(), false, THING_TYPE_SMOKE));
        assertEquals("contradicting rfLevel", XSenseAccountHandler.describe(new XSenseDeviceState(1, 1, 3, -1),
                List.of("rfLevel"), false, THING_TYPE_SMOKE));
        assertEquals("missing batInfo; contradicting rfLevel", XSenseAccountHandler
                .describe(new XSenseDeviceState(1, 1, -1, -1), List.of("rfLevel"), false, THING_TYPE_SMOKE));
        assertEquals("serial number used by several devices of the station, ignored",
                XSenseAccountHandler.describe(null, List.of(), true, THING_TYPE_SMOKE));
    }

    @Test
    void expectsModelSpecificFields() {
        XSenseDeviceState basic = new XSenseDeviceState(1, 0, 3, 3);
        // coPpmPeak is optional and never reported as missing
        assertEquals(List.of("coPpm"), XSenseAccountHandler.missingFields(basic, THING_TYPE_CO));
        assertEquals(List.of(),
                XSenseAccountHandler.missingFields(new XSenseDeviceState(1, 0, 3, 3, 0, -1, -1, -1), THING_TYPE_CO));
        assertEquals(List.of("isOpen"),
                XSenseAccountHandler.missingFields(new XSenseDeviceState(1, -1, 3, 3), THING_TYPE_DOOR));
        assertEquals(List.of("isMoved"),
                XSenseAccountHandler.missingFields(new XSenseDeviceState(1, -1, 3, 3), THING_TYPE_MOTION));
        assertEquals(List.of(), XSenseAccountHandler.missingFields(basic, null));
    }
}
