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
import org.openhab.binding.xsense.internal.api.XSenseHttpTransport;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;

/**
 * Login of the account bridge with malformed Cognito challenges from the scripted fake cloud: the
 * bridge reports a communication error, reconnects with backoff and starts polling once a valid
 * challenge arrives. Retry delays run in units of 10 ms instead of seconds.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseReconnectTest {

    private static final ThingUID SMOKE = new ThingUID(XSenseBindingConstants.THING_TYPE_SMOKE,
            XSenseHandlerChain.STATION_UID, "smoke");

    private final FakeXSenseCloud cloud = new FakeXSenseCloud();
    private @Nullable XSenseHandlerChain chain;

    @AfterEach
    void tearDown() {
        XSenseHandlerChain current = chain;
        if (current != null) {
            current.dispose();
        }
    }

    private XSenseHandlerChain installation() {
        cloud.set("api:103007", stationsWithModels("1", "sm-sn", "sm-id", "XS01-M"));
        cloud.set("shadow:2nd_mainpage", shadow("""
                {"devs":{"sm-sn":{"online":1,"alarmStatus":1,"batInfo":"3","rfLevel":"3"}}}
                """));
        XSenseHandlerChain installation = new XSenseHandlerChain(cloud, new MutableClock(), 10);
        installation.addSensor(XSenseBindingConstants.THING_TYPE_SMOKE, "smoke", "sm-sn", "alarm#smoke",
                "device#lowBattery");
        chain = installation;
        return installation;
    }

    private long count(String endpoint) {
        return cloud.endpoints().stream().filter(endpoint::equals).count();
    }

    @Test
    void malformedChallengesAreRetriedWithBackoffUntilPollingStarts() {
        XSenseHandlerChain installation = installation();
        cloud.then("cognito:InitiateAuth/srp", srpChallenge("\"not-hex\"", "\"a1b2c3\"", "\"c2VjcmV0\""),
                srpChallenge("\"5a7b\"", "\"a1b2c3\"", "\"%%%\""));
        XSenseAccountHandler account = installation.account();

        account.connect();
        ThingStatusInfo failed = installation.status(XSenseHandlerChain.ACCOUNT_UID);
        assertEquals(ThingStatus.OFFLINE, failed.getStatus());
        assertEquals(ThingStatusDetail.COMMUNICATION_ERROR, failed.getStatusDetail());
        String description = String.valueOf(failed.getDescription());
        assertTrue(description.contains("SRP_B"), description);
        assertFalse(description.contains("not-hex"), description);
        // First retry after 30 units, the next one is doubled
        assertEquals(60, account.nextRetryDelay());
        assertFalse(account.isPolling());

        XSenseHandlerChain.waitFor(
                () -> installation.status(XSenseHandlerChain.ACCOUNT_UID).getStatus() == ThingStatus.ONLINE,
                "account online");
        XSenseHandlerChain.waitFor(() -> OnOffType.ON.equals(installation.state(SMOKE, "alarm#smoke")),
                "alarm published");
        assertTrue(account.isPolling());
        assertEquals(30, account.nextRetryDelay());
        assertEquals(3, count("cognito:InitiateAuth/srp"));
        assertEquals(1, count("cognito:RespondToAuthChallenge"));

        List<ThingStatusDetail> details = installation.history(XSenseHandlerChain.ACCOUNT_UID).stream()
                .filter(info -> info.getStatus() == ThingStatus.OFFLINE).map(ThingStatusInfo::getStatusDetail)
                .toList();
        assertEquals(List.of(ThingStatusDetail.COMMUNICATION_ERROR, ThingStatusDetail.COMMUNICATION_ERROR), details);
    }

    @Test
    void rejectedCredentialsAreAConfigurationErrorWithoutRetry() throws InterruptedException {
        XSenseHandlerChain installation = installation();
        cloud.set("cognito:RespondToAuthChallenge", new XSenseHttpTransport.Response(400,
                "{\"__type\":\"NotAuthorizedException\",\"message\":\"Incorrect username or password.\"}"));
        installation.account().connect();
        Thread.sleep(800);

        ThingStatusInfo status = installation.status(XSenseHandlerChain.ACCOUNT_UID);
        assertEquals(ThingStatus.OFFLINE, status.getStatus());
        assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, status.getStatusDetail());
        assertEquals(1, count("cognito:InitiateAuth/srp"));
        assertFalse(installation.account().isPolling());
    }
}
