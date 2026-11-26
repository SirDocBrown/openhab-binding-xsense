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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests the plain text labels of discovery results.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseDiscoveryLabelTest {

    @Test
    void usesTheNameFromTheXSenseApp() {
        assertEquals("X-Sense CO-Melder", XSenseCloudDiscoveryService.deviceLabel("X-Sense CO-Melder", "XC01-M", "0C"));
        assertEquals("Flur", XSenseCloudDiscoveryService.deviceLabel(" Flur ", "XS01-M", "01"));
        assertEquals("Basis", XSenseCloudDiscoveryService.stationLabel("Basis", "SBS50", "S1"));
        assertEquals("MyHome", XSenseCloudDiscoveryService.homeLabel("MyHome", "H1"));
    }

    @Test
    void fallsBackToModelAndSerialNumber() {
        assertEquals("XC01-M 0000000C", XSenseCloudDiscoveryService.deviceLabel(null, "XC01-M", "0000000C"));
        assertEquals("XS01-M 00000001", XSenseCloudDiscoveryService.deviceLabel("  ", "XS01-M", "00000001"));
        assertEquals("SBS50 STATIONTEST01", XSenseCloudDiscoveryService.stationLabel(null, "SBS50", "STATIONTEST01"));
        assertEquals("X-Sense Home HOUSE-TEST-1", XSenseCloudDiscoveryService.homeLabel("", "HOUSE-TEST-1"));
    }
}
