package com.mirth.connect.plugins.datatypes.fhir.server;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.Transformer;
import com.mirth.connect.plugins.datatypes.fhir.FhirDataTypeProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;

public class FhirChannelOptionsTest {

    private static FhirDataTypeProperties fhir(FhirVersion version, boolean validateInbound) {
        FhirDataTypeProperties properties = new FhirDataTypeProperties();
        FhirSerializationProperties p = (FhirSerializationProperties) properties.getSerializationProperties();
        p.setFhirVersion(version);
        p.setValidateInbound(validateInbound);
        return properties;
    }

    private static Transformer transformer(FhirDataTypeProperties inbound, FhirDataTypeProperties outbound) {
        Transformer transformer = new Transformer();
        transformer.setInboundProperties(inbound);
        transformer.setOutboundProperties(outbound);
        return transformer;
    }

    @Test
    public void collectsTheOptionsOfEveryFhirDataTypeThatValidates() {
        Connector source = new Connector("sourceConnector");
        source.setTransformer(transformer(fhir(FhirVersion.R5, true), fhir(FhirVersion.R5, true)));

        // Validation off: no validator, so its version does not count.
        Connector destination = new Connector("Destination 1");
        destination.setTransformer(transformer(fhir(FhirVersion.R4, false), fhir(FhirVersion.R4, false)));
        destination.setResponseTransformer(transformer(fhir(FhirVersion.R4B, true), null));

        Channel channel = new Channel();
        channel.setSourceConnector(source);
        channel.getDestinationConnectors().add(destination);

        List<Map<String, String>> options = FhirChannelOptions.validatorOptions(List.of(channel));
        assertEquals(3, options.size());
        assertEquals("R5", options.get(0).get("fhirVersion"));
        assertEquals("R5", options.get(1).get("fhirVersion"));
        assertEquals("R4B", options.get(2).get("fhirVersion"));
    }

    @Test
    public void ignoresChannelsWithoutFhir() {
        Connector source = new Connector("sourceConnector");
        source.setTransformer(new Transformer());
        Channel channel = new Channel();
        channel.setSourceConnector(source);
        assertEquals(0, FhirChannelOptions.validatorOptions(List.of(channel)).size());
    }
}
