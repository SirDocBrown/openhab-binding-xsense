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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.ApiResponse;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.AwsCredentials;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.ClientInfo;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.House;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.StationList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

/**
 * The {@link XSenseApiClient} implements the X-Sense cloud API: Cognito SRP login, token refresh
 * and the bizCode based application REST API.
 *
 * The protocol has been decoded by the python-xsense and Home Assistant X-Sense projects, see
 * https://github.com/theosnel/python-xsense and https://github.com/Jarnsen/ha-xsense-component_test.
 *
 * @author Markus Michels - Initial contribution
 */
@NonNullByDefault
public class XSenseApiClient {
    private static final String API_URL = "https://api.x-sense-iot.com/app";
    private static final String COGNITO_URL_FORMAT = "https://cognito-idp.%s.amazonaws.com/";
    private static final String COGNITO_TARGET_PREFIX = "AWSCognitoIdentityProviderService.";
    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";

    // AWS IoT data plane (device shadow REST API), region taken from the house's mqttRegion
    private static final String IOT_URL_FORMAT = "https://%s.x-sense-iot.com/things/%s/shadow?name=%s";
    private static final String IOT_SERVICE = "iotdata";
    private static final String IOT_CONTENT_TYPE = "application/x-amz-json-1.0";
    // Mimics the X-Sense app; the same value is sent by the Home Assistant integration
    private static final String IOT_USER_AGENT = "aws-sdk-iOS/2.26.5 iOS/17.3 nl_NL";
    // Expiration format of the temporary AWS credentials, e.g. "2026-07-12 10:15:00+00:00"
    private static final DateTimeFormatter AWS_EXPIRATION_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss[XXX][XX][X]");

    private static final String CLIENT_TYPE_ANDROID = "2";
    private static final String APP_VERSION = "v1.36.0_20260130";
    private static final String APP_CODE = "1360";
    // Fixed integrity hash accepted for unauthenticated requests
    private static final String UNAUTH_MAC = "abcdefg";

    private static final String BIZCODE_CLIENT_INFO = "101001";
    private static final String BIZCODE_AWS_CREDENTIALS = "101003";
    private static final String BIZCODE_QUERY_HOUSES = "102007";
    private static final String BIZCODE_QUERY_STATIONS = "103007";

    // errCode values indicating an expired/invalid session
    private static final Set<String> SESSION_EXPIRED_ERRORS = Set.of("10000008", "10000020");

    private static final long TOKEN_EXPIRY_MARGIN_SEC = 60;
    // Cognito access tokens are valid between 5 minutes and 1 day; the lower bound used here only
    // has to exceed the refresh margin so that a new token is not refreshed again immediately
    static final long MIN_TOKEN_VALIDITY_SEC = TOKEN_EXPIRY_MARGIN_SEC + 1;
    static final long MAX_TOKEN_VALIDITY_SEC = 86400;
    private static final long DEFAULT_TOKEN_VALIDITY_SEC = 3600;

    // Station shadows in the order the X-Sense app reads them; older firmware only provides "mainpage"
    private static final List<String> STATION_SHADOWS = List.of("2nd_mainpage", "mainpage");
    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;

    private final Logger logger = LoggerFactory.getLogger(XSenseApiClient.class);
    private final XSenseHttpTransport transport;
    private final Clock clock;
    private final Gson gson = new Gson();
    private final Object sessionLock = new Object();

    // Cognito app client information (bizCode 101001)
    private @Nullable String cognitoClientId;
    private @Nullable String cognitoRegion;
    private @Nullable String userPoolId;
    private byte[] clientSecret = new byte[0];

    // Session state, guarded by sessionLock; accessToken is additionally volatile so that
    // isLoggedIn() never blocks on a login/refresh in progress
    private String email = "";
    private String password = "";
    // Internal Cognito user name (USER_ID_FOR_SRP), required for the refresh token SECRET_HASH
    private @Nullable String cognitoUsername;
    private volatile @Nullable String accessToken;
    private @Nullable String refreshToken;
    private Instant accessTokenExpiry = Instant.MIN;

    // Temporary AWS credentials for the IoT shadow API (bizCode 101003), cached until shortly
    // before expiration; both fields are volatile as commands arrive on scheduler threads
    private volatile @Nullable AwsCredentials awsCredentials;
    private volatile Instant awsCredentialsExpiry = Instant.EPOCH;

    public XSenseApiClient(XSenseHttpTransport transport) {
        this(transport, Clock.systemUTC());
    }

    /**
     * Creates a client with the given clock, used by tests to let tokens and credentials expire.
     */
    public XSenseApiClient(XSenseHttpTransport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * Performs a full login: fetch the Cognito client information and run the SRP authentication flow.
     */
    public void login(String email, String password) throws XSenseApiException {
        awsCredentials = null;
        synchronized (sessionLock) {
            this.email = email;
            this.password = password;
            fetchClientInfo();
            srpLogin();
        }
    }

    public boolean isLoggedIn() {
        return accessToken != null;
    }

    /**
     * Returns all houses of the account.
     */
    public List<House> getHouses() throws XSenseApiException {
        JsonElement data = apiCall(BIZCODE_QUERY_HOUSES, orderedParams("utctimestamp", "0"));
        List<House> houses = fromJson(data, TypeToken.getParameterized(List.class, House.class).getType());
        return houses != null ? houses : new ArrayList<>();
    }

    /**
     * Returns the base stations of a house including their attached devices.
     */
    public StationList getStations(String houseId) throws XSenseApiException {
        JsonElement data = apiCall(BIZCODE_QUERY_STATIONS, orderedParams("houseId", houseId, "utctimestamp", "0"));
        StationList stations = fromJson(data, StationList.class);
        return stations != null ? stations : new StationList();
    }

    /**
     * Returns temporary AWS credentials for AWS IoT shadow access, cached until shortly before
     * their expiration.
     */
    private AwsCredentials getAwsCredentials() throws XSenseApiException {
        AwsCredentials cached = awsCredentials;
        if (cached != null && clock.instant().isBefore(awsCredentialsExpiry.minusSeconds(TOKEN_EXPIRY_MARGIN_SEC))) {
            return cached;
        }
        return refreshAwsCredentials();
    }

    private AwsCredentials refreshAwsCredentials() throws XSenseApiException {
        String userName;
        synchronized (sessionLock) {
            userName = email;
        }
        JsonElement data = apiCall(BIZCODE_AWS_CREDENTIALS, orderedParams("userName", userName));
        AwsCredentials credentials = fromJson(data, AwsCredentials.class);
        if (credentials == null || credentials.accessKeyId == null || credentials.secretAccessKey == null
                || credentials.sessionToken == null) {
            throw new XSenseApiException("Incomplete AWS credentials response (bizCode 101003)");
        }
        awsCredentialsExpiry = parseAwsExpiration(credentials.expiration);
        awsCredentials = credentials;
        return credentials;
    }

    /**
     * Reads the reported state of an SBS50 and its children from the station shadow.
     *
     * The "2nd_mainpage" shadow is read first and "mainpage" when it does not exist. Rejected AWS
     * credentials are renewed once per call, so at most three shadow requests are sent.
     *
     * @return the "state.reported" object of the shadow, empty when the shadow only has a desired state
     */
    public JsonObject getDeviceStates(String region, String stationSn) throws XSenseApiException {
        if (!region.matches("[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+") || !stationSn.matches("[A-Za-z0-9_-]+")) {
            throw new XSenseApiException("Invalid station shadow address");
        }
        AwsCredentials credentials = getAwsCredentials();
        boolean credentialsRenewed = false;
        int lastStatus = HTTP_NOT_FOUND;
        for (String shadow : STATION_SHADOWS) {
            String url = shadowUrl(region, "SBS50" + stationSn, shadow);
            XSenseHttpTransport.Response response = readShadow(url, region, credentials);
            if (!credentialsRenewed
                    && (response.status() == HTTP_UNAUTHORIZED || response.status() == HTTP_FORBIDDEN)) {
                credentialsRenewed = true;
                credentials = refreshAwsCredentials();
                response = readShadow(url, region, credentials);
            }
            lastStatus = response.status();
            if (lastStatus == HTTP_NOT_FOUND) {
                continue;
            }
            if (lastStatus != HTTP_OK) {
                throw new XSenseApiException("Station shadow " + shadow + " returned HTTP " + lastStatus);
            }
            return XSenseStationReport.reported(fromJson(response.body(), JsonObject.class));
        }
        throw new XSenseApiException("Station shadow returned HTTP " + lastStatus);
    }

    private XSenseHttpTransport.Response readShadow(String url, String mqttRegion, AwsCredentials credentials)
            throws XSenseApiException {
        String accessKeyId = credentials.accessKeyId;
        String secretAccessKey = credentials.secretAccessKey;
        String sessionToken = credentials.sessionToken;
        if (accessKeyId == null || secretAccessKey == null || sessionToken == null) {
            throw new XSenseApiException("Incomplete AWS credentials (bizCode 101003)");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", IOT_CONTENT_TYPE);
        headers.put("User-Agent", IOT_USER_AGENT);
        headers.put("X-Amz-Security-Token", sessionToken);
        Map<String, String> signature = new XSenseAwsSigV4(mqttRegion, IOT_SERVICE).sign("GET", URI.create(url),
                headers, "", accessKeyId, secretAccessKey, clock.instant());
        // The signed headers must be sent exactly as signed; the Host header is set by the transport
        Map<String, String> requestHeaders = new LinkedHashMap<>(headers);
        signature.forEach((key, value) -> {
            if (!"Host".equalsIgnoreCase(key)) {
                requestHeaders.put(key, value);
            }
        });
        return transport.send("GET", url, requestHeaders, null);
    }

    /**
     * Builds the shadow REST URL: https://{mqttRegion}.x-sense-iot.com/things/{thingName}/shadow?name={shadowName}.
     */
    static String shadowUrl(String mqttRegion, String thingName, String shadowName) {
        return String.format(IOT_URL_FORMAT, mqttRegion, XSenseAwsSigV4.uriEncode(thingName),
                XSenseAwsSigV4.uriEncode(shadowName));
    }

    /**
     * Parses the AWS credential expiration ("yyyy-MM-dd HH:mm:ss" plus zone offset); an
     * unparsable value yields the epoch so the credentials are simply never cached.
     */
    static Instant parseAwsExpiration(@Nullable String expiration) {
        if (expiration == null) {
            return Instant.EPOCH;
        }
        try {
            return OffsetDateTime.parse(expiration.trim(), AWS_EXPIRATION_FORMAT).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    // === bizCode application API ===

    private void fetchClientInfo() throws XSenseApiException {
        JsonElement data = unauthenticatedApiCall(BIZCODE_CLIENT_INFO, new LinkedHashMap<>());
        ClientInfo info = fromJson(data, ClientInfo.class);
        String clientId = info != null ? info.clientId : null;
        String encodedSecret = info != null ? info.clientSecret : null;
        String region = info != null ? info.cgtRegion : null;
        String poolId = info != null ? info.userPoolId : null;
        if (info == null || clientId == null || encodedSecret == null || region == null || poolId == null) {
            throw new XSenseApiException("Incomplete client info response (bizCode 101001)");
        }
        cognitoClientId = clientId;
        clientSecret = decodeClientSecret(encodedSecret);
        cognitoRegion = region;
        userPoolId = poolId;
    }

    private JsonElement apiCall(String bizCode, LinkedHashMap<String, String> params) throws XSenseApiException {
        String token;
        String mac;
        synchronized (sessionLock) {
            ensureFreshToken();
            token = accessToken;
            mac = calculateMac(params, clientSecret);
        }
        if (token == null) {
            throw new XSenseApiException("Not logged in", true);
        }
        return executeApiCall(bizCode, params, mac, token);
    }

    private JsonElement unauthenticatedApiCall(String bizCode, LinkedHashMap<String, String> params)
            throws XSenseApiException {
        return executeApiCall(bizCode, params, UNAUTH_MAC, null);
    }

    private JsonElement executeApiCall(String bizCode, LinkedHashMap<String, String> params, String mac,
            @Nullable String authToken) throws XSenseApiException {
        JsonObject body = new JsonObject();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            body.addProperty(entry.getKey(), entry.getValue());
        }
        body.addProperty("clientType", CLIENT_TYPE_ANDROID);
        body.addProperty("mac", mac);
        body.addProperty("appVersion", APP_VERSION);
        body.addProperty("bizCode", bizCode);
        body.addProperty("appCode", APP_CODE);

        String responseBody = httpPost(API_URL, body.toString(), "application/json", authToken, Map.of());

        ApiResponse response = fromJson(responseBody, ApiResponse.class);
        Integer reCode = response != null ? response.reCode : null;
        if (response == null || reCode == null) {
            throw new XSenseApiException("Unparsable API response for bizCode " + bizCode);
        }
        if (reCode != 200) {
            String errCode = response.errCode;
            boolean expired = errCode != null && SESSION_EXPIRED_ERRORS.contains(errCode);
            throw new XSenseApiException(
                    "API error for bizCode " + bizCode + ": " + reCode + "/" + errCode + " " + response.reMsg, expired);
        }
        JsonElement reData = response.reData;
        return reData != null ? reData : new JsonObject();
    }

    /**
     * Calculates the request integrity hash: MD5 over the concatenated parameter values
     * (in request order) followed by the client secret.
     */
    static String calculateMac(LinkedHashMap<String, String> params, byte[] clientSecret) throws XSenseApiException {
        StringBuilder sb = new StringBuilder();
        for (String value : params.values()) {
            sb.append(value);
        }
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(sb.toString().getBytes(StandardCharsets.UTF_8));
            md5.update(clientSecret);
            StringBuilder hex = new StringBuilder();
            for (byte b : md5.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new XSenseApiException("MD5 not available", e);
        }
    }

    /**
     * The client secret is delivered base64 encoded with 4 leading and 1 trailing garbage byte.
     */
    static byte[] decodeClientSecret(String encoded) throws XSenseApiException {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new XSenseApiException("Unable to decode client secret", e);
        }
        if (decoded.length <= 5) {
            throw new XSenseApiException("Unexpected client secret length: " + decoded.length);
        }
        byte[] secret = new byte[decoded.length - 5];
        System.arraycopy(decoded, 4, secret, 0, secret.length);
        return secret;
    }

    // === Cognito authentication ===

    private void srpLogin() throws XSenseApiException {
        String clientId = cognitoClientId;
        String poolId = userPoolId;
        if (clientId == null || poolId == null) {
            throw new XSenseApiException("Cognito client info not initialized");
        }

        XSenseCognitoSrp srp = new XSenseCognitoSrp(poolId, clientId, clientSecret);

        JsonObject initRequest = new JsonObject();
        initRequest.addProperty("AuthFlow", "USER_SRP_AUTH");
        initRequest.addProperty("ClientId", clientId);
        initRequest.add("AuthParameters", toJsonObject(srp.getAuthParameters(email)));
        JsonObject initResponse = cognitoCall("InitiateAuth", initRequest);

        JsonObject challengeParameters = getAsJsonObject(initResponse, "ChallengeParameters");
        Map<String, String> challenge = new HashMap<>();
        for (String key : challengeParameters.keySet()) {
            JsonElement value = challengeParameters.get(key);
            if (value != null && value.isJsonPrimitive()) {
                challenge.put(key, value.getAsString());
            }
        }
        String userIdForSrp = challenge.get(XSenseCognitoSrp.PARAM_USER_ID_FOR_SRP);
        if (userIdForSrp != null) {
            cognitoUsername = userIdForSrp;
        }

        JsonObject challengeRequest = new JsonObject();
        challengeRequest.addProperty("ChallengeName", "PASSWORD_VERIFIER");
        challengeRequest.addProperty("ClientId", clientId);
        challengeRequest.add("ChallengeResponses", toJsonObject(srp.processChallenge(challenge, password)));
        JsonObject challengeResponse = cognitoCall("RespondToAuthChallenge", challengeRequest);

        parseAuthenticationResult(getAsJsonObject(challengeResponse, "AuthenticationResult"));
        logger.debug("X-Sense login successful for {}", XSenseDtoUtil.maskEmail(email));
    }

    /**
     * Refreshes the access token when it is about to expire; falls back to a full re-login when
     * the refresh token has been invalidated. Must be called while holding sessionLock.
     */
    private void ensureFreshToken() throws XSenseApiException {
        if (accessToken == null) {
            throw new XSenseApiException("Not logged in", true);
        }
        if (clock.instant().isBefore(accessTokenExpiry.minusSeconds(TOKEN_EXPIRY_MARGIN_SEC))) {
            return;
        }
        String clientId = cognitoClientId;
        String refresh = refreshToken;
        if (clientId == null || refresh == null) {
            srpLogin();
            return;
        }
        try {
            String userName = cognitoUsername;
            JsonObject authParameters = new JsonObject();
            authParameters.addProperty("REFRESH_TOKEN", refresh);
            authParameters.addProperty("SECRET_HASH",
                    XSenseCognitoSrp.secretHash(clientSecret, userName != null ? userName : email, clientId));
            JsonObject request = new JsonObject();
            request.addProperty("AuthFlow", "REFRESH_TOKEN_AUTH");
            request.addProperty("ClientId", clientId);
            request.add("AuthParameters", authParameters);
            request.add("UserContextData", new JsonObject());
            JsonObject response = cognitoCall("InitiateAuth", request);
            parseAuthenticationResult(getAsJsonObject(response, "AuthenticationResult"));
            logger.debug("X-Sense access token refreshed");
        } catch (XSenseApiException e) {
            logger.debug("Token refresh failed ({}), performing full login", e.getMessage());
            srpLogin();
        }
    }

    /**
     * Validates a Cognito authentication result completely before taking it over, so that a
     * malformed response leaves the previous session untouched and is reported as a controlled
     * {@link XSenseApiException} instead of a runtime exception in the polling thread.
     */
    private void parseAuthenticationResult(JsonObject result) throws XSenseApiException {
        String newAccessToken = tokenValue(result, "AccessToken");
        if (newAccessToken == null) {
            throw new XSenseApiException("Cognito response misses AccessToken");
        }
        String newRefreshToken = tokenValue(result, "RefreshToken");
        long validity = tokenValidity(result.get("ExpiresIn"));
        accessToken = newAccessToken;
        if (newRefreshToken != null) {
            // The refresh flow does not return a new refresh token, keep the existing one then
            refreshToken = newRefreshToken;
        }
        accessTokenExpiry = clock.instant().plusSeconds(validity);
    }

    /**
     * Returns a token as non-blank string, null when absent.
     *
     * @throws XSenseApiException when the token is present but not a non-blank string
     */
    static @Nullable String tokenValue(JsonObject result, String name) throws XSenseApiException {
        JsonElement value = result.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw new XSenseApiException("Cognito response contains an invalid " + name);
        }
        return value.getAsString();
    }

    /**
     * Returns the token validity in seconds: an integral JSON number between
     * {@link #MIN_TOKEN_VALIDITY_SEC} and {@link #MAX_TOKEN_VALIDITY_SEC}; one hour when absent.
     *
     * @throws XSenseApiException for other types, fractions and values out of range
     */
    static long tokenValidity(@Nullable JsonElement expiresIn) throws XSenseApiException {
        if (expiresIn == null || expiresIn.isJsonNull()) {
            return DEFAULT_TOKEN_VALIDITY_SEC;
        }
        if (!expiresIn.isJsonPrimitive() || !expiresIn.getAsJsonPrimitive().isNumber()) {
            throw new XSenseApiException("Cognito response contains an invalid ExpiresIn");
        }
        long seconds;
        try {
            seconds = expiresIn.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new XSenseApiException("Cognito response contains an invalid ExpiresIn", e);
        }
        if (seconds < MIN_TOKEN_VALIDITY_SEC || seconds > MAX_TOKEN_VALIDITY_SEC) {
            throw new XSenseApiException("Cognito response contains an ExpiresIn out of range: " + seconds);
        }
        return seconds;
    }

    private JsonObject cognitoCall(String target, JsonObject request) throws XSenseApiException {
        String region = cognitoRegion;
        if (region == null) {
            throw new XSenseApiException("Cognito client info not initialized");
        }
        String url = String.format(COGNITO_URL_FORMAT, region);
        String responseBody = httpPost(url, request.toString(), COGNITO_CONTENT_TYPE, null,
                Map.of("X-Amz-Target", COGNITO_TARGET_PREFIX + target));
        JsonObject response = fromJson(responseBody, JsonObject.class);
        if (response == null) {
            throw new XSenseApiException("Unparsable Cognito response for " + target);
        }
        JsonElement type = response.get("__type");
        if (type != null) {
            String typeName = type.isJsonPrimitive() ? type.getAsString() : type.toString();
            JsonElement messageElement = response.get("message");
            String message = messageElement != null && messageElement.isJsonPrimitive() ? messageElement.getAsString()
                    : "";
            boolean authFailure = typeName.contains("NotAuthorized") || typeName.contains("UserNotFound");
            throw new XSenseApiException("Cognito error " + typeName + ": " + message, authFailure);
        }
        return response;
    }

    // === HTTP + JSON helpers ===

    private String httpPost(String url, String body, String contentType, @Nullable String authToken,
            Map<String, String> additionalHeaders) throws XSenseApiException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        if (authToken != null) {
            headers.put("Authorization", authToken);
        }
        headers.putAll(additionalHeaders);
        XSenseHttpTransport.Response response = transport.send("POST", url, headers, body);
        int status = response.status();
        String content = response.body();
        if (status >= 200 && status < 300) {
            return content;
        }
        // Cognito reports errors with HTTP 400 and a JSON body carrying the error type in "__type"
        if (status == HTTP_BAD_REQUEST && isCognitoError(content)) {
            return content;
        }
        // Redirects are not followed (credentials must not be forwarded) and are errors as well
        throw new XSenseApiException("HTTP " + status + " from " + url);
    }

    private boolean isCognitoError(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            return json != null && json.has("__type");
        } catch (JsonParseException | ClassCastException | IllegalStateException e) {
            return false;
        }
    }

    private <T> @Nullable T fromJson(String json, Class<T> clazz) throws XSenseApiException {
        try {
            return gson.fromJson(json, clazz);
        } catch (JsonParseException | ClassCastException | IllegalStateException | NumberFormatException e) {
            throw new XSenseApiException("Invalid JSON response", e);
        }
    }

    private <T> @Nullable T fromJson(JsonElement json, java.lang.reflect.Type type) throws XSenseApiException {
        try {
            return gson.fromJson(json, type);
        } catch (JsonParseException | ClassCastException | IllegalStateException | NumberFormatException e) {
            throw new XSenseApiException("Invalid JSON response", e);
        }
    }

    private static JsonObject toJsonObject(Map<String, String> map) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static JsonObject getAsJsonObject(JsonObject parent, String name) throws XSenseApiException {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonObject()) {
            throw new XSenseApiException("Cognito response misses object: " + name);
        }
        return element.getAsJsonObject();
    }

    private static LinkedHashMap<String, String> orderedParams(String... keyValues) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return params;
    }
}
