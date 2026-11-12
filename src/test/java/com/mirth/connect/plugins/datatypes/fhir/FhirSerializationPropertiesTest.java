package com.mirth.connect.plugins.datatypes.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.mirth.connect.model.datatype.DataTypePropertyDescriptor;
import com.mirth.connect.plugins.datatypes.fhir.FhirBatchProperties.SplitType;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FailOn;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.InvalidMessages;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.OutputFormat;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.TransformerFormat;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.UnknownCodeSystems;

/** Options as the Administrator passes them (enums) and as the web admin's Web Support plugin does (text). */
public class FhirSerializationPropertiesTest {

    /** What Web Support's _serialize endpoint makes of "key=value" lines: Booleans and Integers converted, the rest text. */
    private static Map<String, Object> asWebSupportSendsThem(Map<String, DataTypePropertyDescriptor> descriptors, Map<String, String> lines) {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, String> line : lines.entrySet()) {
            Object current = descriptors.get(line.getKey()).getValue();
            map.put(line.getKey(), current instanceof Boolean ? (Object) Boolean.parseBoolean(line.getValue()) : line.getValue());
        }
        return map;
    }

    @Test
    public void acceptsEnumNamesAsText() {
        FhirSerializationProperties p = new FhirSerializationProperties();
        Map<String, String> lines = new HashMap<>();
        lines.put("fhirVersion", "R5");
        lines.put("transformerFormat", "JSON");
        lines.put("outputFormat", "XML");
        lines.put("invalidMessages", "Accept");
        lines.put("failOn", "Warnings");
        lines.put("unknownCodeSystems", "Error");
        lines.put("validateInbound", "false");
        lines.put("profiles", "");
        p.setProperties(asWebSupportSendsThem(p.getPropertyDescriptors(), lines));

        assertEquals(FhirVersion.R5, p.getFhirVersion());
        assertEquals(TransformerFormat.JSON, p.getTransformerFormat());
        assertEquals(OutputFormat.XML, p.getOutputFormat());
        assertEquals(InvalidMessages.Accept, p.getInvalidMessages());
        assertEquals(FailOn.Warnings, p.getFailOn());
        assertEquals(UnknownCodeSystems.Error, p.getUnknownCodeSystems());
        assertFalse(p.isValidateInbound());
    }

    @Test
    public void stillAcceptsTheEnumsAndBooleans() {
        FhirSerializationProperties p = new FhirSerializationProperties();
        Map<String, Object> map = new HashMap<>();
        map.put("fhirVersion", FhirVersion.R4B);
        map.put("failOn", FailOn.Warnings);
        map.put("prettyPrint", Boolean.FALSE);
        map.put("validateOutbound", "true");
        p.setProperties(map);

        assertEquals(FhirVersion.R4B, p.getFhirVersion());
        assertEquals(FailOn.Warnings, p.getFailOn());
        assertFalse(p.isPrettyPrint());
        assertTrue(p.isValidateOutbound());
    }

    @Test
    public void roundTripsEveryOptionAsText() {
        FhirSerializationProperties p = new FhirSerializationProperties();
        Map<String, DataTypePropertyDescriptor> descriptors = p.getPropertyDescriptors();
        Map<String, String> lines = new HashMap<>();
        for (Map.Entry<String, DataTypePropertyDescriptor> d : descriptors.entrySet()) {
            lines.put(d.getKey(), String.valueOf(d.getValue().getValue()));
        }
        FhirSerializationProperties copy = new FhirSerializationProperties();
        copy.setProperties(asWebSupportSendsThem(descriptors, lines));
        assertEquals(p.getPurgedProperties(), copy.getPurgedProperties());
    }

    @Test
    public void batchSplitTypeAsText() {
        FhirBatchProperties b = new FhirBatchProperties();
        Map<String, Object> map = new HashMap<>();
        map.put("splitType", "JavaScript");
        b.setProperties(map);
        assertEquals(SplitType.JavaScript, b.getSplitType());
    }
}
