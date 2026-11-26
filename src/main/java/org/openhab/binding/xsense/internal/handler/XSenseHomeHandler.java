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

import static org.openhab.binding.xsense.internal.XSenseBindingConstants.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.House;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.Station;
import org.openhab.binding.xsense.internal.api.dto.XSenseApiDto.StationList;
import org.openhab.binding.xsense.internal.config.XSenseHomeConfiguration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;

/**
 * The {@link XSenseHomeHandler} represents a home (house) of the X-Sense account. It receives the
 * house state from the account handler's inventory polls and propagates the station list to its
 * base station child handlers.
 *
 * @author Markus Michels - Initial contribution
 * @author Andreas Rothstein - Simplified inventory propagation
 */
@NonNullByDefault
public class XSenseHomeHandler extends BaseBridgeHandler {

    // Written in initialize(), read via getHouseId() from the account handler's poll thread
    private volatile XSenseHomeConfiguration config = new XSenseHomeConfiguration();

    public XSenseHomeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        config = getConfigAs(XSenseHomeConfiguration.class);
        if (config.houseId.isBlank()) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.config-error-house-id");
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        requestRefresh();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The home has no channels
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            requestRefresh();
        } else {
            updateStatus(ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    public String getHouseId() {
        return config.houseId;
    }

    /**
     * Requests an inventory refresh from the account handler.
     */
    public void requestRefresh() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof XSenseAccountHandler accountHandler) {
            accountHandler.requestRefresh();
        }
    }

    /**
     * Applies the current inventory data to this thing and its base station children. Called by the
     * account handler after each successful inventory poll.
     *
     * @param house the house data, or null when the house is no longer part of the account
     * @param stationList the stations of the house, or null when unavailable
     */
    public void updateFromInventory(@Nullable House house, @Nullable StationList stationList) {
        if (house == null) {
            updateStatus(ThingStatusDetail.GONE, "@text/offline.home-not-found");
            return;
        }
        Map<String, String> properties = new HashMap<>();
        String houseName = house.houseName;
        if (houseName != null) {
            properties.put(PROPERTY_HOUSE_NAME, houseName);
        }
        properties.put(PROPERTY_HOUSE_ID, config.houseId);
        updateProperties(properties);
        updateStatus(ThingStatus.ONLINE);

        List<Station> stations = stationList != null ? stationList.stations : null;
        for (Thing thing : getThing().getThings()) {
            ThingHandler handler = thing.getHandler();
            if (handler instanceof XSenseStationHandler stationHandler) {
                stationHandler.updateFromInventory(findStation(stations, stationHandler.getStationSn()));
            }
        }
    }

    private static @Nullable Station findStation(@Nullable List<Station> stations, String stationSn) {
        if (stations != null) {
            for (Station station : stations) {
                if (stationSn.equals(station.stationSn)) {
                    return station;
                }
            }
        }
        return null;
    }
}
