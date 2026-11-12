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

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.Transformer;
import com.mirth.connect.model.datatype.DataTypeProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirDataTypeProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties;

/** The validator options the FHIR data types of channels use. Apart from FhirChannelPlugin so it can be tested without the engine's plugin classes. */
final class FhirChannelOptions {

    private FhirChannelOptions() {}

    /** The options of every FHIR data type with validation in these channels. */
    static List<Map<String, String>> validatorOptions(Collection<Channel> channels) {
        List<Map<String, String>> options = new ArrayList<>();
        for (Channel channel : channels) {
            List<Connector> connectors = new ArrayList<>();
            if (channel.getSourceConnector() != null) {
                connectors.add(channel.getSourceConnector());
            }
            if (channel.getDestinationConnectors() != null) {
                connectors.addAll(channel.getDestinationConnectors());
            }
            for (Connector connector : connectors) {
                for (Transformer transformer : new Transformer[] { connector.getTransformer(), connector.getResponseTransformer() }) {
                    if (transformer != null) {
                        add(options, transformer.getInboundProperties());
                        add(options, transformer.getOutboundProperties());
                    }
                }
            }
        }
        return options;
    }

    private static void add(List<Map<String, String>> options, DataTypeProperties properties) {
        if (properties instanceof FhirDataTypeProperties) {
            FhirSerializationProperties p = (FhirSerializationProperties) properties.getSerializationProperties();
            if (p != null && (p.isValidateInbound() || p.isValidateOutbound())) {
                options.add(p.engineOptions());
            }
        }
    }
}
