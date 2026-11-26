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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * {@link XSenseHttpTransport} based on the HTTP client of the Java 21 runtime. It needs no package
 * imports and therefore runs independently of the Jetty version shipped with openHAB.
 *
 * The client never follows redirects, see {@link XSenseHttpTransport}. It owns its connection pool
 * and selector thread, so the owner must {@link #close()} it when it is no longer needed.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public class XSenseJavaHttpTransport implements XSenseHttpTransport, AutoCloseable {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    // Waiting time for running requests when the binding is stopped
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    // Headers set by the HTTP client itself; setting them is rejected by java.net.http
    private static final Set<String> MANAGED_HEADERS = Set.of("host", "content-length", "connection", "expect",
            "upgrade");

    private final HttpClient client;
    private final Duration requestTimeout;

    public XSenseJavaHttpTransport() {
        this(CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    XSenseJavaHttpTransport(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
    }

    HttpClient client() {
        return client;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    @Override
    public Response send(String method, String url, Map<String, String> headers, @Nullable String body)
            throws XSenseApiException {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach((name, value) -> {
                if (!MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    builder.header(name, value);
                }
            });
            request = builder.build();
        } catch (IllegalArgumentException e) {
            throw new XSenseApiException("Invalid request to " + url + ": " + e.getMessage(), e);
        }
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), Objects.requireNonNullElse(response.body(), ""));
        } catch (HttpTimeoutException e) {
            throw new XSenseApiException("Request to " + url + " timed out", e);
        } catch (IOException e) {
            throw new XSenseApiException("Request to " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XSenseApiException("Request to " + url + " interrupted", e);
        }
    }

    /**
     * Aborts running requests and releases the connection pool and the selector thread.
     */
    @Override
    public void close() {
        client.shutdownNow();
        try {
            client.awaitTermination(CLOSE_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
