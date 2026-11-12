/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.plugins.ChannelPlugin;
import com.mirth.connect.plugins.datatypes.fhir.FhirEngine;
import com.mirth.connect.plugins.datatypes.fhir.FhirEngineLoader;
import com.mirth.connect.plugins.datatypes.fhir.FhirValidatorUsage;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;

/**
 * Tells the FHIR engine which validator options the deployed channels use, and releases the
 * validators and FHIR specifications of undeployed or changed channels right after a deploy or
 * undeploy. Without it they would stay in memory until a restart.
 */
public class FhirChannelPlugin implements ChannelPlugin {

    private static final Logger logger = LogManager.getLogger(FhirChannelPlugin.class);

    @Override
    public String getPluginPointName() {
        return "FHIR Validator Release";
    }

    public FhirChannelPlugin() {
        FhirValidatorUsage.setInUse(FhirChannelPlugin::deployedOptions);
    }

    @Override
    public void start() {}

    @Override
    public void stop() {}

    /** After all channels of a deploy are deployed. */
    @Override
    public void deploy(ServerEventContext context) {
        release();
    }

    /** After all channels of an undeploy are undeployed. */
    @Override
    public void undeploy(ServerEventContext context) {
        release();
    }

    // @formatter:off
    @Override public void save(Channel channel, ServerEventContext context) {}
    @Override public void remove(Channel channel, ServerEventContext context) {}
    @Override public void deploy(Channel channel, ServerEventContext context) {}
    @Override public void undeploy(String channelId, ServerEventContext context) {}
    // @formatter:on

    /**
     * Everything the deployed channels do not use goes at once, including validators used only from
     * scripts (FhirTools): they are built again when a script needs them.
     */
    private static void release() {
        FhirEngine engine = FhirEngineLoader.loadedEngine();
        if (engine == null) {
            return; // nothing was validated yet
        }
        try {
            FhirValidatorUsage.releaseNow(engine, 0);
        } catch (RuntimeException e) {
            logger.warn("FHIR data type: could not release unused validators: " + e.getMessage());
        }
    }

    private static List<Map<String, String>> deployedOptions() {
        ChannelController channels = ControllerFactory.getFactory().createChannelController();
        Collection<String> ids = ControllerFactory.getFactory().createEngineController().getDeployedIds();
        List<Channel> deployed = new ArrayList<>();
        for (String id : ids) {
            Channel channel = channels.getDeployedChannelById(id);
            if (channel != null) {
                deployed.add(channel);
            }
        }
        return FhirChannelOptions.validatorOptions(deployed);
    }
}
