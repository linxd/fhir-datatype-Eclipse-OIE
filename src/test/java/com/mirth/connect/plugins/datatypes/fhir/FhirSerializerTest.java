package com.mirth.connect.plugins.datatypes.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.BeforeClass;
import org.junit.Test;

import com.mirth.connect.donkey.model.message.MessageSerializerException;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.InvalidMessages;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.OutputFormat;

/** The data type with the real HAPI FHIR validator (loaded from the test classpath). */
public class FhirSerializerTest {

    private static final String VALID_PATIENT = "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"active\":true,"
            + "\"name\":[{\"family\":\"Jansen\",\"given\":[\"Piet\"]}],\"gender\":\"male\",\"birthDate\":\"1980-01-01\"}";

    /** An unknown gender code, a date that is not a date and an element that does not exist. */
    private static final String INVALID_PATIENT = "{\"resourceType\":\"Patient\",\"gender\":\"x\",\"birthDate\":\"01-01-1980\",\"shoeSize\":42}";

    @BeforeClass
    public static void directEngine() {
        System.setProperty("fhir.engine.direct", "true");
    }

    private static FhirSerializer serializer(FhirSerializationProperties p) {
        return new FhirSerializer(new SerializerProperties(p, null, null));
    }

    @Test
    public void acceptsAValidPatientAndFillsTheConnectorMap() throws Exception {
        FhirSerializer s = serializer(new FhirSerializationProperties());
        Map<String, Object> map = new HashMap<>();
        s.populateMetaData(VALID_PATIENT, map);
        assertEquals("Patient", map.get("mirth_type"));
        assertEquals("R4", map.get("mirth_version"));
        assertEquals(map.get(FhirSerializer.ISSUES_VARIABLE).toString(), Boolean.TRUE, map.get(FhirSerializer.VALID_VARIABLE));
        String xml = s.toXML(VALID_PATIENT);
        assertTrue(xml, xml.startsWith("<Patient>\n  <id value=\"p1\"/>"));
    }

    @Test
    public void rejectsAnInvalidPatientWithTheIssues() throws Exception {
        FhirSerializer s = serializer(new FhirSerializationProperties());
        s.populateMetaData(INVALID_PATIENT, new HashMap<>());
        try {
            s.toXML(INVALID_PATIENT);
            fail("expected the message to be rejected");
        } catch (MessageSerializerException e) {
            String text = e.getMessage();
            assertTrue(text, text.startsWith("The FHIR message is invalid"));
            assertTrue(text, text.contains("gender"));
            assertTrue(text, text.contains("birthDate"));
            assertTrue(text, text.contains("shoeSize"));
        }
    }

    @Test
    public void acceptsAnInvalidPatientWhenConfiguredAndReportsInTheMap() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setInvalidMessages(InvalidMessages.Accept);
        FhirSerializer s = serializer(p);
        Map<String, Object> map = new HashMap<>();
        s.populateMetaData(INVALID_PATIENT, map);
        assertEquals(Boolean.FALSE, map.get(FhirSerializer.VALID_VARIABLE));
        assertTrue((Integer) map.get(FhirSerializer.ISSUE_COUNT_VARIABLE) >= 3);
        assertTrue(s.toXML(INVALID_PATIENT).contains("<shoeSize value=\"42\"/>"));
    }

    @Test
    public void rejectsWithoutTransformerToo() throws Exception {
        FhirSerializer s = serializer(new FhirSerializationProperties());
        assertTrue(s.isSerializationRequired(true));
        try {
            s.transformWithoutSerializing(INVALID_PATIENT, serializer(new FhirSerializationProperties()));
            fail();
        } catch (MessageSerializerException e) {
            assertTrue(e.getMessage().startsWith("The FHIR message is invalid"));
        }
    }

    @Test
    public void writesJsonOrXmlOut() throws Exception {
        FhirSerializer s = serializer(new FhirSerializationProperties());
        String transformerXml = s.toXML(VALID_PATIENT);
        String json = s.fromXML(transformerXml);
        assertTrue(json, json.contains("\"resourceType\": \"Patient\""));
        assertTrue(json, json.contains("\"given\": [ \"Piet\" ]"));

        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setOutputFormat(OutputFormat.XML);
        String xml = serializer(p).fromXML(transformerXml);
        assertTrue(xml, xml.startsWith("<Patient xmlns=\"http://hl7.org/fhir\">"));

        // JSON in, XML out without a transformer: the outbound serializer converts.
        String converted = s.transformWithoutSerializing(VALID_PATIENT, serializer(p));
        assertTrue(converted, converted.startsWith("<Patient xmlns=\"http://hl7.org/fhir\">"));
    }

    /** As a transformer leaves it after msg.appendChild(...): birthDate and gender after name, id last. */
    private static final String PATIENT_OUT_OF_ORDER = "<Patient>"
            + "<extension url=\"http://example.org/fhir/StructureDefinition/shoe-size\"><valueInteger value=\"42\"/></extension>"
            + "<name><given value=\"Piet\"/><family value=\"Jansen\"/></name>"
            + "<birthDate value=\"1980-01-01\"/><gender value=\"male\"/>"
            + "<text><status value=\"generated\"/><div xmlns=\"http://www.w3.org/1999/xhtml\">Piet Jansen</div></text>"
            + "<id value=\"p1\"/></Patient>";

    private static FhirSerializationProperties xmlOut() {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setOutputFormat(OutputFormat.XML);
        return p;
    }

    private static void assertInOrder(String text, String... parts) {
        int at = -1;
        for (String part : parts) {
            int next = text.indexOf(part);
            assertTrue(part + " missing or out of order in:\n" + text, next > at);
            at = next;
        }
    }

    @Test
    public void writesXmlOutInTheOrderOfTheSpecification() throws Exception {
        String xml = serializer(xmlOut()).fromXML(PATIENT_OUT_OF_ORDER);
        assertTrue(xml, xml.startsWith("<Patient xmlns=\"http://hl7.org/fhir\">"));
        assertInOrder(xml, "<id value=\"p1\"/>", "<text>", "<extension url=", "<name>", "<family value=\"Jansen\"/>",
                "<given value=\"Piet\"/>", "<gender value=\"male\"/>", "<birthDate value=\"1980-01-01\"/>");
        // The narrative keeps its XHTML namespace, the extension its value.
        assertTrue(xml, xml.contains("<div xmlns=\"http://www.w3.org/1999/xhtml\">Piet Jansen</div>"));
        assertTrue(xml, xml.contains("<valueInteger value=\"42\"/>"));
    }

    @Test
    public void refusesUnknownElementsInXmlOut() throws Exception {
        try {
            serializer(xmlOut()).fromXML("<Patient><id value=\"p1\"/><shoeSize value=\"42\"/></Patient>");
            fail("expected the unknown element to be refused");
        } catch (MessageSerializerException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("The transformer output is not valid FHIR"));
            assertTrue(e.getMessage(), e.getMessage().contains("shoeSize"));
        }
    }

    @Test
    public void leavesXmlAsItIsWithoutTransformer() throws Exception {
        FhirSerializationProperties in = new FhirSerializationProperties();
        in.setValidateInbound(false);
        String xml = "<Patient xmlns=\"http://hl7.org/fhir\"><gender value=\"male\"/><id value=\"p1\"/></Patient>";
        assertEquals(xml, serializer(in).transformWithoutSerializing(xml, serializer(xmlOut())));
    }

    /** An XXE attempt: an external entity that would read a local file. */
    private static final String XXE_PATIENT = "<?xml version=\"1.0\"?>\n<!DOCTYPE Patient [<!ENTITY xxe SYSTEM \"file:///c:/windows/win.ini\">]>\n"
            + "<Patient xmlns=\"http://hl7.org/fhir\"><id value=\"p1\"/><name><family value=\"&xxe;\"/></name></Patient>";

    private static void assertRefused(String what, Callable<?> call) {
        try {
            call.call();
            fail(what + ": expected the DOCTYPE to be refused");
        } catch (Exception e) {
            assertTrue(what + ": " + e.getMessage(), e.getMessage().contains("must not contain a DOCTYPE"));
        }
    }

    @Test
    public void refusesXmlWithADoctypeOnEveryPath() {
        FhirSerializationProperties accept = new FhirSerializationProperties();
        accept.setInvalidMessages(InvalidMessages.Accept);
        assertRefused("inbound", () -> serializer(accept).toXML(XXE_PATIENT));
        assertRefused("without transformer", () -> serializer(accept).transformWithoutSerializing(XXE_PATIENT, serializer(xmlOut())));
        assertRefused("outbound", () -> serializer(xmlOut()).fromXML(XXE_PATIENT));
        assertRefused("FhirTools.validate", () -> FhirTools.validate(XXE_PATIENT));
        assertRefused("FhirTools.toJson", () -> FhirTools.toJson(XXE_PATIENT));
    }

    @Test
    public void refusesXmlOutThatHasUnknownElementsInsteadOfDroppingThem() throws Exception {
        try {
            serializer(new FhirSerializationProperties()).fromXML("<Patient><shoeSize value=\"42\"/></Patient>");
            fail();
        } catch (MessageSerializerException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("shoeSize"));
        }
    }

    @Test
    public void validatesOutboundWhenAsked() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setValidateOutbound(true);
        try {
            serializer(p).fromXML("<Patient><gender value=\"x\"/></Patient>");
            fail();
        } catch (MessageSerializerException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("The outbound FHIR message is invalid"));
        }
    }

    @Test
    public void validatesAgainstYourOwnProfile() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setProfiles(new File("src/test/resources/profiles").getAbsolutePath());
        p.setRequiredProfile("http://example.org/fhir/StructureDefinition/patient-with-birthdate");
        FhirSerializer s = serializer(p);

        String withoutBirthDate = "{\"resourceType\":\"Patient\",\"gender\":\"female\"}";
        s.populateMetaData(withoutBirthDate, new HashMap<>());
        try {
            s.toXML(withoutBirthDate);
            fail();
        } catch (MessageSerializerException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Patient.birthDate"));
        }
        s.populateMetaData(VALID_PATIENT, new HashMap<>());
        s.toXML(VALID_PATIENT);
    }

    @Test
    public void validatesXmlInput() throws Exception {
        FhirSerializer s = serializer(new FhirSerializationProperties());
        String xml = "<Patient xmlns=\"http://hl7.org/fhir\"><gender value=\"female\"/></Patient>";
        Map<String, Object> map = new HashMap<>();
        s.populateMetaData(xml, map);
        assertEquals(Boolean.TRUE, map.get(FhirSerializer.VALID_VARIABLE));
        assertEquals("<Patient><gender value=\"female\"/></Patient>", s.toXML(xml));
    }

    private static final String BUNDLE = "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
            + "{\"fullUrl\":\"urn:uuid:61ebe359-bfdc-4613-8bf2-c5e300945f0a\",\"resource\":{\"resourceType\":\"Patient\","
            + "\"text\":{\"status\":\"generated\",\"div\":\"<div xmlns=\\\"http://www.w3.org/1999/xhtml\\\"><p>Piet Jansen</p></div>\"},"
            + "\"identifier\":[{\"system\":\"http://fhir.nl/fhir/NamingSystem/bsn\",\"value\":\"111222333\"}],"
            + "\"name\":[{\"family\":\"Jansen\",\"given\":[\"Piet\"]}],\"gender\":\"male\",\"birthDate\":\"1980-01-01\"},"
            + "\"request\":{\"method\":\"POST\",\"url\":\"Patient\"}},"
            + "{\"fullUrl\":\"urn:uuid:88f151c0-a954-468a-88bd-5ae15c08e059\",\"resource\":{\"resourceType\":\"Observation\",\"status\":\"final\","
            + "\"category\":[{\"coding\":[{\"system\":\"http://terminology.hl7.org/CodeSystem/observation-category\",\"code\":\"vital-signs\"}]}],"
            + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"8867-4\",\"display\":\"Heart rate\"}]},"
            + "\"subject\":{\"reference\":\"urn:uuid:61ebe359-bfdc-4613-8bf2-c5e300945f0a\"},\"effectiveDateTime\":\"2026-09-25T10:00:00+02:00\","
            + "\"valueQuantity\":{\"value\":72,\"unit\":\"beats/minute\",\"system\":\"http://unitsofmeasure.org\",\"code\":\"/min\"}},"
            + "\"request\":{\"method\":\"POST\",\"url\":\"Observation\"}}]}";

    @Test
    public void validatesARealisticTransactionBundle() throws Exception {
        FhirValidation v = FhirTools.validate(BUNDLE);
        assertTrue(v.summary(), v.isValid());

        // The same bundle without Observation.status (required, 1..1) and with a unit that is not UCUM.
        String broken = BUNDLE.replace("\"status\":\"final\",", "").replace("\"code\":\"/min\"", "\"code\":\"beats\"");
        FhirValidation b = FhirTools.validate(broken);
        assertFalse(b.isValid());
        assertTrue(b.summary(), b.summary().contains("Observation.status"));
        assertTrue(b.summary(), b.summary().contains("beats"));

        // Round trip through the transformer XML and back to JSON keeps it valid.
        FhirSerializer s = serializer(new FhirSerializationProperties());
        String json = s.fromXML(s.toXML(BUNDLE));
        assertTrue(FhirTools.validate(json).summary(), FhirTools.validate(json).isValid());
    }

    /** Batch processing (#6): every entry is its own message, validated on its own. */
    @Test
    public void validatesEverySplitEntryOnItsOwn() throws Exception {
        String bundle = BUNDLE.substring(0, BUNDLE.length() - 2) + ",{\"fullUrl\":\"urn:uuid:3\",\"resource\":" + INVALID_PATIENT + "}]}";
        java.util.List<FhirBundleSplitter.Part> parts = FhirBundleSplitter.split(bundle);
        assertEquals(3, parts.size());

        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setInvalidMessages(InvalidMessages.Accept);
        FhirSerializer s = serializer(p);
        Boolean[] expected = { Boolean.TRUE, Boolean.TRUE, Boolean.FALSE };
        String[] types = { "Patient", "Observation", "Patient" };
        for (int i = 0; i < 3; i++) {
            Map<String, Object> map = new HashMap<>();
            s.populateMetaData(parts.get(i).getMessage(), map);
            assertEquals(types[i], map.get("mirth_type"));
            assertEquals(map.get(FhirSerializer.ISSUES_VARIABLE).toString(), expected[i], map.get(FhirSerializer.VALID_VARIABLE));
        }
    }

    @Test
    public void jsHelperValidates() throws Exception {
        FhirValidation v = FhirTools.validate(INVALID_PATIENT);
        assertFalse(v.isValid());
        assertTrue(v.summary(), v.summary().contains("shoeSize"));
        assertTrue(FhirTools.validate(VALID_PATIENT).isValid());
    }
}
