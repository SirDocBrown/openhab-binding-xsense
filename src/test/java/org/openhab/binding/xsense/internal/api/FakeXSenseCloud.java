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

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Scripted stand-in for the X-Sense cloud (application API, Cognito and AWS IoT shadow) used as
 * {@link XSenseHttpTransport} in unit tests. It answers by endpoint, records the sequence of calls
 * and returns anonymized fixture data only.
 *
 * Endpoint names: "api:&lt;bizCode&gt;", "cognito:&lt;target&gt;" ("cognito:InitiateAuth/srp" and
 * "cognito:InitiateAuth/refresh" for the two flows) and "shadow:&lt;shadow name&gt;".
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public class FakeXSenseCloud implements XSenseHttpTransport {

    public static final String USER_POOL_ID = "eu-central-1_TestPool";
    public static final String REGION = "eu-central-1";
    public static final String HOUSE_ID = "HOUSE-TEST-1";
    public static final String STATION_SN = "STATIONTEST01";

    /** Request as seen by the fake cloud. */
    public record Call(String endpoint, Map<String, String> headers, @Nullable String body) {
    }

    private final Map<String, Deque<Response>> scripted = new HashMap<>();
    private final Map<String, Response> defaults = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private int tokenCounter;

    public FakeXSenseCloud() {
        defaults.put("api:101001", api("""
                {"clientId":"test-client","clientSecret":"%s","cgtRegion":"eu-central-1","userPoolId":"%s"}
                """.formatted(Base64.getEncoder().encodeToString("0000test-secretX".getBytes(StandardCharsets.UTF_8)),
                USER_POOL_ID)));
        defaults.put("cognito:InitiateAuth/srp", ok(SRP_CHALLENGE));
        defaults.put("cognito:InitiateAuth/refresh", ok(authResult("access-refreshed", null, "3600")));
        defaults.put("cognito:RespondToAuthChallenge", ok(authResult(nextToken(), "refresh-1", "3600")));
        defaults.put("api:101003", api("""
                {"accessKeyId":"ASIATEST","secretAccessKey":"test/secret","sessionToken":"session-token",
                 "expiration":"2099-01-01 00:00:00+00:00"}
                """));
        defaults.put("api:102007", api("""
                [{"houseId":"%s","houseName":"Test home","mqttRegion":"%s"}]
                """.formatted(HOUSE_ID, REGION)));
    }

    /** Fixed default response of an endpoint. */
    public synchronized FakeXSenseCloud set(String endpoint, Response response) {
        defaults.put(endpoint, response);
        return this;
    }

    /** One-time responses of an endpoint, used before its default response. */
    public synchronized FakeXSenseCloud then(String endpoint, Response... responses) {
        Deque<Response> queue = scripted.computeIfAbsent(endpoint, k -> new ArrayDeque<>());
        for (Response response : responses) {
            queue.add(response);
        }
        return this;
    }

    public synchronized List<String> endpoints() {
        return calls.stream().map(Call::endpoint).toList();
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized void clearCalls() {
        calls.clear();
    }

    public synchronized String nextToken() {
        return "access-" + (++tokenCounter);
    }

    @Override
    public synchronized Response send(String method, String url, Map<String, String> headers, @Nullable String body)
            throws XSenseApiException {
        String endpoint = endpoint(url, headers, body);
        calls.add(new Call(endpoint, Map.copyOf(headers), body));
        Deque<Response> queue = scripted.get(endpoint);
        Response response = queue != null ? queue.poll() : null;
        if (response == null) {
            response = defaults.get(endpoint);
        }
        if (response == null) {
            throw new XSenseApiException("Unexpected request " + endpoint);
        }
        if (response == CRASH) {
            throw new IllegalStateException("Simulated programming error in the HTTP stack");
        }
        return response;
    }

    private static String endpoint(String url, Map<String, String> headers, @Nullable String body) {
        if (url.contains("cognito-idp")) {
            String target = headers.getOrDefault("X-Amz-Target", "");
            String name = "cognito:" + target.substring(target.indexOf('.') + 1);
            if (name.endsWith("InitiateAuth") && body != null) {
                // SRP login and token refresh use the same target
                name += body.contains("REFRESH_TOKEN_AUTH") ? "/refresh" : "/srp";
            }
            return name;
        }
        if (url.contains("/shadow?name=")) {
            return "shadow:" + url.substring(url.indexOf("name=") + 5);
        }
        JsonObject request = JsonParser.parseString(body != null ? body : "{}").getAsJsonObject();
        return "api:" + request.get("bizCode").getAsString();
    }

    private static final String SRP_CHALLENGE = """
            {"ChallengeName":"PASSWORD_VERIFIER","ChallengeParameters":{"USERNAME":"user-id-test",
             "USER_ID_FOR_SRP":"user-id-test","SALT":"a1b2c3d4e5f6","SRP_B":"5a7b9c1d3e5f7a9b",
             "SECRET_BLOCK":"c2VjcmV0LWJsb2Nr"}}
            """;

    /** Response that makes the transport throw an unchecked exception. */
    public static final Response CRASH = new Response(-1, "crash");

    public static Response ok(String body) {
        return new Response(200, body);
    }

    public static Response status(int status) {
        return new Response(status, "");
    }

    /** Successful application API response with the given reData. */
    public static Response api(String reData) {
        return ok("{\"reCode\":200,\"reMsg\":\"success\",\"reData\":" + reData.strip() + "}");
    }

    /** Cognito authentication result; the values are inserted as raw JSON. */
    public static String authResult(String accessToken, @Nullable String refreshToken, String expiresInJson) {
        return "{\"AuthenticationResult\":{\"AccessToken\":\"" + accessToken + "\""
                + (refreshToken != null ? ",\"RefreshToken\":\"" + refreshToken + "\"" : "") + ",\"ExpiresIn\":"
                + expiresInJson + "}}";
    }

    /** Cognito authentication result with raw JSON values for all fields. */
    public static String rawAuthResult(String accessTokenJson, String refreshTokenJson, String expiresInJson) {
        return "{\"AuthenticationResult\":{\"AccessToken\":" + accessTokenJson + ",\"RefreshToken\":" + refreshTokenJson
                + ",\"ExpiresIn\":" + expiresInJson + "}}";
    }

    /** Station list of {@link #HOUSE_ID} with one SBS50 and the given children (deviceSn, deviceId). */
    public static Response stations(String stationOnline, String... children) {
        StringBuilder devices = new StringBuilder();
        for (int i = 0; i < children.length; i += 2) {
            if (devices.length() > 0) {
                devices.append(',');
            }
            devices.append("{\"deviceSn\":\"").append(children[i]).append("\",\"deviceId\":\"").append(children[i + 1])
                    .append("\",\"deviceType\":\"XS01-M\",\"deviceName\":\"Detector\"}");
        }
        return api("{\"stations\":[{\"stationSn\":\"" + STATION_SN + "\",\"category\":\"SBS50\",\"onLine\":"
                + stationOnline + ",\"devices\":[" + devices + "]}]}");
    }

    /**
     * Station list of {@link #HOUSE_ID} with one SBS50 and the given children as triples of
     * deviceSn, deviceId and deviceType.
     */
    public static Response stationsWithModels(String stationOnline, String... children) {
        StringBuilder devices = new StringBuilder();
        for (int i = 0; i < children.length; i += 3) {
            if (devices.length() > 0) {
                devices.append(',');
            }
            devices.append("{\"deviceSn\":\"").append(children[i]).append("\",\"deviceId\":\"")
                    .append(children[i + 1]).append("\",\"deviceType\":\"").append(children[i + 2])
                    .append("\",\"deviceName\":\"Detector\"}");
        }
        return api("{\"stations\":[{\"stationSn\":\"" + STATION_SN + "\",\"category\":\"SBS50\",\"onLine\":"
                + stationOnline + ",\"devices\":[" + devices + "]}]}");
    }

    /** SRP challenge with the given raw JSON values for SRP_B, SALT and SECRET_BLOCK. */
    public static Response srpChallenge(String srpBJson, String saltJson, String secretBlockJson) {
        return ok("{\"ChallengeName\":\"PASSWORD_VERIFIER\",\"ChallengeParameters\":{\"USERNAME\":\"user-id-test\","
                + "\"USER_ID_FOR_SRP\":\"user-id-test\",\"SALT\":" + saltJson + ",\"SRP_B\":" + srpBJson
                + ",\"SECRET_BLOCK\":" + secretBlockJson + "}}");
    }

    /** Station shadow document with the given "state.reported" object. */
    public static Response shadow(String reported) {
        return ok("{\"state\":{\"reported\":" + reported.strip() + "}}");
    }
}
