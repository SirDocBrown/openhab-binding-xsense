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
package org.openhab.binding.xsense.internal;

import static org.openhab.binding.xsense.internal.XSenseBindingConstants.*;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.xsense.internal.api.XSenseJavaHttpTransport;
import org.openhab.binding.xsense.internal.config.XSenseBindingConfiguration;
import org.openhab.binding.xsense.internal.handler.XSenseAccountHandler;
import org.openhab.binding.xsense.internal.handler.XSenseHomeHandler;
import org.openhab.binding.xsense.internal.handler.XSenseSensorHandler;
import org.openhab.binding.xsense.internal.handler.XSenseStationHandler;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;

/**
 * The {@link XSenseHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Markus Michels - Initial contribution
 * @author Andreas Rothstein - Java HTTP client independent of the runtime's Jetty version
 */
@NonNullByDefault
@Component(configurationPid = "binding.xsense", service = ThingHandlerFactory.class)
public class XSenseHandlerFactory extends BaseThingHandlerFactory {

    // Java 21 HTTP client owned by this factory: the binding must not import Jetty packages, as the
    // Jetty version differs between openHAB releases (e.g. 9.4 in 5.3.0.M5, 12.1 in later snapshots)
    private final XSenseJavaHttpTransport transport;
    // Volatile for safe publication after @Modified callbacks
    private volatile XSenseBindingConfiguration bindingConfig = new XSenseBindingConfiguration();

    @Activate
    public XSenseHandlerFactory(Map<String, Object> properties) {
        transport = new XSenseJavaHttpTransport();
        bindingConfig = XSenseBindingConfiguration.fromConfig(properties);
    }

    @Override
    @Deactivate
    protected void deactivate(ComponentContext componentContext) {
        super.deactivate(componentContext);
        // Handlers are disposed by now; release the connection pool and the selector thread
        transport.close();
    }

    @Modified
    protected void modified(Map<String, Object> properties) {
        bindingConfig = XSenseBindingConfiguration.fromConfig(properties);
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (THING_TYPE_ACCOUNT.equals(thingTypeUID)) {
            // Supplier so that later binding configuration changes reach existing handlers
            return new XSenseAccountHandler((Bridge) thing, transport, () -> bindingConfig.refreshInterval);
        } else if (THING_TYPE_HOME.equals(thingTypeUID)) {
            return new XSenseHomeHandler((Bridge) thing);
        } else if (THING_TYPE_STATION.equals(thingTypeUID)) {
            return new XSenseStationHandler((Bridge) thing);
        } else if (SENSOR_THING_TYPES.contains(thingTypeUID)) {
            return new XSenseSensorHandler(thing);
        }
        return null;
    }
}
