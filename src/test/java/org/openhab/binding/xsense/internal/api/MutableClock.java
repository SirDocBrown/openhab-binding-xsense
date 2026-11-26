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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Clock for tests that can be advanced, e.g. beyond the validity of an access token.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-11-26T10:00:00Z");

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(@Nullable ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
