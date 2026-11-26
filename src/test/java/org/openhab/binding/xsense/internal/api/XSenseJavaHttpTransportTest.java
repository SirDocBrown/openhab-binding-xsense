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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests the Java 21 HTTP transport against a local HTTP server: no redirects, timeouts, headers and
 * the release of the client.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class XSenseJavaHttpTransportTest {

    private @Nullable HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private String base = "";

    @BeforeEach
    void startServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.createContext("/redirect", exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Location", base + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        httpServer.createContext("/target", exchange -> {
            record(exchange);
            respond(exchange, 200, "target");
        });
        httpServer.createContext("/echo", exchange -> {
            record(exchange);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("X-Test")
                    + " " + exchange.getRequestHeaders().getFirst("Content-Type") + " " + body);
        });
        httpServer.createContext("/slow", exchange -> {
            record(exchange);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        httpServer.start();
        server = httpServer;
        base = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        HttpServer httpServer = server;
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private void record(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requests.add(exchange.getRequestURI().getPath() + (authorization != null ? " " + authorization : ""));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void clientIsConfiguredWithoutRedirectsAndWithTimeouts() {
        try (XSenseJavaHttpTransport transport = new XSenseJavaHttpTransport()) {
            HttpClient client = transport.client();
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
            assertEquals(Duration.ofSeconds(30), client.connectTimeout().orElseThrow());
            assertEquals(Duration.ofSeconds(15), transport.requestTimeout());
        }
    }

    @Test
    void redirectOfAnAuthenticatedRequestIsNotFollowed() throws XSenseApiException {
        try (XSenseJavaHttpTransport transport = new XSenseJavaHttpTransport()) {
            XSenseHttpTransport.Response response = transport.send("GET", base + "/redirect",
                    Map.of("Authorization", "secret-token"), null);
            assertEquals(302, response.status());
            assertEquals(List.of("/redirect secret-token"), requests);
        }
    }

    @Test
    void sendsMethodHeadersAndBodyAndSkipsManagedHeaders() throws XSenseApiException {
        try (XSenseJavaHttpTransport transport = new XSenseJavaHttpTransport()) {
            XSenseHttpTransport.Response response = transport.send("POST", base + "/echo",
                    Map.of("X-Test", "1", "Content-Type", "application/json", "Host", "example.invalid"), "{\"a\":1}");
            assertEquals(200, response.status());
            assertEquals("POST 1 application/json {\"a\":1}", response.body());
        }
    }

    @Test
    void requestTimeoutIsAControlledError() {
        try (XSenseJavaHttpTransport transport = new XSenseJavaHttpTransport(Duration.ofSeconds(5),
                Duration.ofMillis(300))) {
            XSenseApiException error = assertThrows(XSenseApiException.class,
                    () -> transport.send("GET", base + "/slow", Map.of(), null));
            assertTrue(error.getMessage().contains("timed out"), error.getMessage());
        }
    }

    @Test
    void closeReleasesTheClient() throws InterruptedException {
        XSenseJavaHttpTransport transport = new XSenseJavaHttpTransport();
        transport.close();
        assertTrue(transport.client().isTerminated());
        assertThrows(XSenseApiException.class, () -> transport.send("GET", base + "/target", Map.of(), null));
    }
}
