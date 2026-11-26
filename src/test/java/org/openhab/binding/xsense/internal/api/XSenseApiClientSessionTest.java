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
package org.openhab.binding.xsense.internal.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.xsense.internal.api.FakeXSenseCloud.*;

import java.time.Duration;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the session handling (token renewal) and the station shadow request sequence of the
 * {@link XSenseApiClient} against the scripted {@link FakeXSenseCloud}.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseApiClientSessionTest {

    private final FakeXSenseCloud cloud = new FakeXSenseCloud();
    private final MutableClock clock = new MutableClock();
    private final XSenseApiClient client = new XSenseApiClient(cloud, clock);

    private void login() throws XSenseApiException {
        client.login("user@example.invalid", "password");
        assertEquals(List.of("api:101001", "cognito:InitiateAuth/srp", "cognito:RespondToAuthChallenge"),
                cloud.endpoints());
        cloud.clearCalls();
    }

    private String lastAuthorization() {
        List<FakeXSenseCloud.Call> calls = cloud.calls();
        return calls.get(calls.size() - 1).headers().getOrDefault("Authorization", "");
    }

    // === Token renewal ===

    @Test
    void failedRenewalIsAControlledErrorAndTheNextValidRenewalRecovers() throws XSenseApiException {
        login();
        clock.advance(Duration.ofHours(1));
        // Refresh returns a string ExpiresIn, the fallback login an out-of-range ExpiresIn with a token
        cloud.then("cognito:InitiateAuth/refresh", ok(authResult("access-invalid-1", null, "\"3600\"")));
        cloud.then("cognito:RespondToAuthChallenge", ok(authResult("access-invalid-2", "refresh-invalid", "-5")));

        XSenseApiException error = assertThrows(XSenseApiException.class, client::getHouses);
        assertFalse(error.isAuthenticationFailure());
        assertEquals(
                List.of("cognito:InitiateAuth/refresh", "cognito:InitiateAuth/srp", "cognito:RespondToAuthChallenge"),
                cloud.endpoints());
        assertTrue(client.isLoggedIn());
        cloud.clearCalls();

        // Next poll: the session was not taken over, so the token is renewed again, now successfully
        assertEquals(1, client.getHouses().size());
        assertEquals(List.of("cognito:InitiateAuth/refresh", "api:102007"), cloud.endpoints());
        // The refresh token of the invalid response was not taken over
        String refreshRequest = cloud.calls().get(0).body();
        assertNotNull(refreshRequest);
        assertTrue(refreshRequest.contains("\"refresh-1\""), refreshRequest);
        assertEquals("access-refreshed", lastAuthorization());

        // The renewed token is valid for its ExpiresIn: no further renewal within that time
        cloud.clearCalls();
        clock.advance(Duration.ofMinutes(30));
        client.getHouses();
        assertEquals(List.of("api:102007"), cloud.endpoints());
    }

    @ParameterizedTest
    @ValueSource(strings = { //
            "{\"AuthenticationResult\":{\"RefreshToken\":\"r\",\"ExpiresIn\":3600}}", // no AccessToken
            "{\"AuthenticationResult\":{\"AccessToken\":null,\"ExpiresIn\":3600}}",
            "{\"AuthenticationResult\":{\"AccessToken\":42,\"ExpiresIn\":3600}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"\",\"ExpiresIn\":3600}}",
            "{\"AuthenticationResult\":{\"AccessToken\":[\"a\"],\"ExpiresIn\":3600}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"RefreshToken\":{},\"ExpiresIn\":3600}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"RefreshToken\":7,\"ExpiresIn\":3600}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":\"3600\"}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":\"soon\"}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":3600.5}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":true}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":{}}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":0}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":-1}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":60}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":86401}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":9223372036854775808}}",
            "{\"AuthenticationResult\":{\"AccessToken\":\"a\",\"ExpiresIn\":1e400}}", "{\"AuthenticationResult\":[]}",
            "[]", "\"text\"", "not json" })
    void invalidAuthenticationResultIsRejectedBeforeTheSessionIsTakenOver(String response) {
        cloud.set("cognito:RespondToAuthChallenge", ok(response));
        assertThrows(XSenseApiException.class, () -> client.login("user@example.invalid", "password"));
        assertFalse(client.isLoggedIn());
    }

    @Test
    void validTokenValidityRange() throws XSenseApiException {
        assertEquals(3600, XSenseApiClient.tokenValidity(null));
        assertEquals(61, XSenseApiClient.tokenValidity(JsonParser.parseString("61")));
        assertEquals(86400, XSenseApiClient.tokenValidity(JsonParser.parseString("86400")));
        assertEquals(3600, XSenseApiClient.tokenValidity(JsonParser.parseString("3600.0")));
    }

    // === Cognito SRP challenge ===

    @ParameterizedTest
    @ValueSource(strings = { //
            "\"zz-not-hex\"|\"a1b2c3\"|\"c2VjcmV0\"", // SRP_B not hexadecimal
            "\"\"|\"a1b2c3\"|\"c2VjcmV0\"", // SRP_B empty
            "null|\"a1b2c3\"|\"c2VjcmV0\"", // SRP_B missing
            "{\"x\":1}|\"a1b2c3\"|\"c2VjcmV0\"", // SRP_B object
            "\"0\"|\"a1b2c3\"|\"c2VjcmV0\"", // SRP_B mod N is zero
            "\"5a7b\"|\"salt!\"|\"c2VjcmV0\"", // SALT not hexadecimal
            "\"5a7b\"|\" \"|\"c2VjcmV0\"", // SALT blank
            "\"5a7b\"|\"a1b2c3\"|\"%%%not-base64\"", // SECRET_BLOCK not Base64
            "\"5a7b\"|\"a1b2c3\"|\"\"", // SECRET_BLOCK empty
            "\"5a7b\"|\"a1b2c3\"|[1,2]" }) // SECRET_BLOCK array
    void malformedChallengeIsAControlledCommunicationError(String parameters) {
        String[] values = parameters.split("\\|");
        cloud.then("cognito:InitiateAuth/srp", srpChallenge(values[0], values[1], values[2]));
        XSenseApiException error = assertThrows(XSenseApiException.class,
                () -> client.login("user@example.invalid", "password"));
        assertFalse(error.isAuthenticationFailure());
        assertFalse(client.isLoggedIn());
        // The challenge was not answered and its content is not part of the message
        assertFalse(cloud.endpoints().contains("cognito:RespondToAuthChallenge"));
        String message = String.valueOf(error.getMessage());
        for (String secret : List.of("zz-not-hex", "salt!", "%%%not-base64", "password", "user@example.invalid")) {
            assertFalse(message.contains(secret), message);
        }
    }

    @Test
    void validChallengeAfterAMalformedOneLogsIn() throws XSenseApiException {
        cloud.then("cognito:InitiateAuth/srp", srpChallenge("\"not-hex\"", "\"a1b2c3\"", "\"c2VjcmV0\""));
        assertThrows(XSenseApiException.class, () -> client.login("user@example.invalid", "password"));
        client.login("user@example.invalid", "password");
        assertTrue(client.isLoggedIn());
        assertEquals(1, cloud.endpoints().stream().filter("cognito:RespondToAuthChallenge"::equals).count());
    }

    @Test
    void rejectedCredentialsRemainAnAuthenticationFailure() {
        cloud.then("cognito:RespondToAuthChallenge", new XSenseHttpTransport.Response(400,
                "{\"__type\":\"NotAuthorizedException\",\"message\":\"Incorrect username or password.\"}"));
        XSenseApiException error = assertThrows(XSenseApiException.class,
                () -> client.login("user@example.invalid", "password"));
        assertTrue(error.isAuthenticationFailure());
    }

    // === Station shadow ===

    @Test
    void forbiddenThenRenewedCredentialsThenNotFoundStillFallsBackToMainpage() throws XSenseApiException {
        login();
        cloud.then("api:101003",
                api("""
                        {"accessKeyId":"ASIAFIRST","secretAccessKey":"s1","sessionToken":"token-1","expiration":"2099-01-01 00:00:00+00:00"}
                        """),
                api("""
                        {"accessKeyId":"ASIASECOND","secretAccessKey":"s2","sessionToken":"token-2","expiration":"2099-01-01 00:00:00+00:00"}
                        """));
        cloud.then("shadow:2nd_mainpage", status(403), status(404));
        cloud.set("shadow:mainpage", shadow("{\"devs\":{\"SN\":{\"alarmStatus\":\"0\"}}}"));

        JsonObject reported = client.getDeviceStates(REGION, STATION_SN);

        assertTrue(reported.has("devs"));
        assertEquals(
                List.of("api:101003", "shadow:2nd_mainpage", "api:101003", "shadow:2nd_mainpage", "shadow:mainpage"),
                cloud.endpoints());
        List<FakeXSenseCloud.Call> calls = cloud.calls();
        assertEquals("token-1", calls.get(1).headers().get("X-Amz-Security-Token"));
        assertEquals("token-2", calls.get(3).headers().get("X-Amz-Security-Token"));
        assertEquals("token-2", calls.get(4).headers().get("X-Amz-Security-Token"));
        assertTrue(calls.get(4).headers().get("Authorization").contains("ASIASECOND/"));
        assertFalse(calls.get(4).headers().containsKey("Host"));
    }

    @Test
    void credentialsAreRenewedAtMostOncePerShadowRead() throws XSenseApiException {
        login();
        cloud.set("shadow:2nd_mainpage", status(403));
        assertThrows(XSenseApiException.class, () -> client.getDeviceStates(REGION, STATION_SN));
        assertEquals(List.of("api:101003", "shadow:2nd_mainpage", "api:101003", "shadow:2nd_mainpage"),
                cloud.endpoints());

        cloud.clearCalls();
        cloud.set("shadow:2nd_mainpage", status(404)).set("shadow:mainpage", status(401));
        assertThrows(XSenseApiException.class, () -> client.getDeviceStates(REGION, STATION_SN));
        assertEquals(List.of("shadow:2nd_mainpage", "shadow:mainpage", "api:101003", "shadow:mainpage"),
                cloud.endpoints());
    }

    @Test
    void missingShadowsEndWithAnErrorAfterBothNames() throws XSenseApiException {
        login();
        cloud.set("shadow:2nd_mainpage", status(404)).set("shadow:mainpage", status(404));
        assertThrows(XSenseApiException.class, () -> client.getDeviceStates(REGION, STATION_SN));
        assertEquals(List.of("api:101003", "shadow:2nd_mainpage", "shadow:mainpage"), cloud.endpoints());
    }

    @Test
    void redirectsAreErrorsAndNotFollowed() throws XSenseApiException {
        login();
        cloud.set("shadow:2nd_mainpage", status(302));
        assertThrows(XSenseApiException.class, () -> client.getDeviceStates(REGION, STATION_SN));
        assertEquals(List.of("api:101003", "shadow:2nd_mainpage"), cloud.endpoints());

        cloud.set("api:102007", status(301));
        assertThrows(XSenseApiException.class, client::getHouses);
    }

    @Test
    void desiredOnlyShadowYieldsNoReportedState() throws XSenseApiException {
        login();
        cloud.set("shadow:2nd_mainpage", ok("{\"state\":{\"desired\":{\"devs\":{\"SN\":{\"alarmStatus\":\"1\"}}}}}"));
        assertEquals(0, client.getDeviceStates(REGION, STATION_SN).size());
    }
}
