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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Minimal HTTP transport used by the {@link XSenseApiClient}. It decouples the API client from a
 * concrete HTTP library so that the binding does not depend on the Jetty version of the runtime
 * and the request sequence can be verified in unit tests.
 *
 * Implementations must not follow redirects: all requests are signed or carry an access token,
 * and a redirect must neither forward these credentials nor be accepted as a valid response.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public interface XSenseHttpTransport {

    /**
     * HTTP response with status code and body.
     */
    record Response(int status, String body) {
    }

    /**
     * Sends a request and returns the response of the addressed server.
     *
     * @param method HTTP method, GET or POST
     * @param url absolute URL
     * @param headers request headers; headers managed by the HTTP stack itself (e.g. Host) are skipped
     * @param body request body for POST, null for GET
     * @throws XSenseApiException on transport failures and timeouts
     */
    Response send(String method, String url, Map<String, String> headers, @Nullable String body)
            throws XSenseApiException;
}
