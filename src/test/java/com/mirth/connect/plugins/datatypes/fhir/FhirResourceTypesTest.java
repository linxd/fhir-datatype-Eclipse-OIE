package com.mirth.connect.plugins.datatypes.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.MessageSerializerException;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.InvalidMessages;
import com.mirth.connect.plugins.datatypes.fhir.server.FhirAutoResponder;

/** The Resource Types property (#25): the resource types a connector accepts. */
public class FhirResourceTypesTest {

    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"active\":true,"
            + "\"name\":[{\"family\":\"Jansen\",\"given\":[\"Piet\"]}],\"gender\":\"male\",\"birthDate\":\"1980-01-01\"}";
    private static final String INVALID_PATIENT = "{\"resourceType\":\"Patient\",\"gender\":\"x\",\"birthDate\":\"01-01-1980\"}";
    private static final String OBSERVATION = "{\"resourceType\":\"Observation\",\"status\":\"final\","
            + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"8867-4\"}]}}";
    private static final String ENCOUNTER_XML = "<Encounter xmlns=\"http://hl7.org/fhir\"><status value=\"finished\"/><class><system value=\"http://terminology.hl7.org/CodeSystem/v3-ActCode\"/><code value=\"AMB\"/></class></Encounter>";
    private static final String REFUSED_OBSERVATION = "Resource type Observation is not in Resource Types (Patient)";

    @BeforeClass
    public static void directEngine() {
        System.setProperty("fhir.engine.direct", "true");
    }

    private static FhirSerializationProperties properties(String resourceTypes) {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setResourceTypes(resourceTypes);
        return p;
    }

    private static FhirSerializer serializer(FhirSerializationProperties p) {
        return new FhirSerializer(new SerializerProperties(p, null, null));
    }

    private static String bundle() throws Exception {
        return new String(Files.readAllBytes(Paths.get("examples", "observation-bundle.json")), StandardCharsets.UTF_8);
    }

    private static String rejection(FhirSerializer s, String message) throws Exception {
        try {
            s.toXML(message);
        } catch (MessageSerializerException e) {
            return e.getMessage();
        }
        fail("expected the message to be refused");
        return null;
    }

    // ------------------------------------------------------------------ the list

    @Test
    public void readsTheListLeniently() {
        assertEquals(Arrays.asList("Patient", "Observation", "Bundle"), Arrays.asList(FhirResourceTypes.parse(" Patient, Observation,,Bundle ").toArray()));
        assertEquals(Arrays.asList("Patient", "Observation"), Arrays.asList(FhirResourceTypes.parse("Patient;Observation Patient").toArray()));
        assertTrue(FhirResourceTypes.parse("").isEmpty());
        assertTrue(FhirResourceTypes.parse(" , ").isEmpty());
        assertTrue(FhirResourceTypes.parse(null).isEmpty());
    }

    @Test
    public void checksTheRootAndEveryBundleEntryInJsonAndXml() throws Exception {
        LinkedHashSet<String> patientAndBundle = new LinkedHashSet<>(Arrays.asList("Patient", "Bundle"));
        List<Map<String, String>> issues = FhirResourceTypes.check(bundle(), patientAndBundle);
        assertEquals(1, issues.size());
        assertEquals("error", issues.get(0).get(FhirEngine.SEVERITY));
        assertEquals("Bundle.entry[1].resource", issues.get(0).get(FhirEngine.LOCATION));
        assertEquals("Resource type Observation is not in Resource Types (Patient, Bundle)", issues.get(0).get(FhirEngine.MESSAGE));
        assertEquals(FhirResourceTypes.MESSAGE_ID, issues.get(0).get(FhirEngine.MESSAGE_ID));

        String xml = FhirXml.jsonToXml(bundle(), true);
        List<Map<String, String>> xmlIssues = FhirResourceTypes.check(xml, patientAndBundle);
        assertEquals(1, xmlIssues.size());
        assertEquals("Bundle.entry[1].resource", xmlIssues.get(0).get(FhirEngine.LOCATION));

        // The Bundle itself, and a Bundle inside an entry.
        String nested = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + bundle() + "}]}";
        List<Map<String, String>> nestedIssues = FhirResourceTypes.check(nested, new LinkedHashSet<>(Arrays.asList("Patient")));
        assertEquals(3, nestedIssues.size());
        assertEquals("Bundle", nestedIssues.get(0).get(FhirEngine.LOCATION));
        assertEquals("Bundle.entry[0].resource", nestedIssues.get(1).get(FhirEngine.LOCATION));
        assertEquals("Bundle.entry[0].resource.entry[1].resource", nestedIssues.get(2).get(FhirEngine.LOCATION));

        assertTrue(FhirResourceTypes.check(bundle(), new LinkedHashSet<>(Arrays.asList("Bundle", "Patient", "Observation"))).isEmpty());
        assertTrue(FhirResourceTypes.check(bundle(), new LinkedHashSet<String>()).isEmpty());
        assertTrue("unreadable: left to validation", FhirResourceTypes.check("not FHIR", patientAndBundle).isEmpty());
    }

    // ------------------------------------------------------------------ the data type

    @Test
    public void aPatientValidatesAsWithoutTheProperty() throws Exception {
        for (String patient : new String[] { PATIENT, INVALID_PATIENT }) {
            FhirSerializationProperties without = new FhirSerializationProperties();
            without.setInvalidMessages(InvalidMessages.Accept);
            FhirSerializationProperties with = properties("Patient");
            with.setInvalidMessages(InvalidMessages.Accept);
            Map<String, Object> a = new HashMap<>();
            Map<String, Object> b = new HashMap<>();
            serializer(without).populateMetaData(patient, a);
            serializer(with).populateMetaData(patient, b);
            assertEquals(a, b);
        }
        assertTrue(serializer(properties("Patient")).toXML(PATIENT).startsWith("<Patient>"));
    }

    @Test
    public void refusesAnotherTypeWithAClearError() throws Exception {
        FhirSerializer s = serializer(properties("Patient"));
        String text = rejection(s, OBSERVATION);
        assertTrue(text, text.startsWith("The FHIR message is invalid (1 issue(s)):"));
        assertTrue(text, text.endsWith("error Observation: " + REFUSED_OBSERVATION));

        String xmlText = rejection(s, ENCOUNTER_XML);
        assertTrue(xmlText, xmlText.endsWith("Resource type Encounter is not in Resource Types (Patient)"));

        try {
            s.transformWithoutSerializing(OBSERVATION, serializer(new FhirSerializationProperties()));
            fail("expected the message to be refused without a transformer too");
        } catch (MessageSerializerException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(REFUSED_OBSERVATION));
        }
    }

    @Test
    public void refusesABundleWithAnEntryOfAnotherType() throws Exception {
        String text = rejection(serializer(properties("Patient, Bundle")), bundle());
        assertTrue(text, text.contains("error Bundle.entry[1].resource: Resource type Observation is not in Resource Types (Patient, Bundle)"));

        FhirSerializer all = serializer(properties("Bundle, Patient, Observation"));
        assertTrue(all.toXML(bundle()).startsWith("<Bundle>"));
    }

    @Test
    public void acceptModePutsTheRefusalInTheConnectorMap() throws Exception {
        FhirSerializationProperties p = properties("Patient");
        p.setInvalidMessages(InvalidMessages.Accept);
        FhirSerializer s = serializer(p);
        Map<String, Object> map = new HashMap<>();
        s.populateMetaData(OBSERVATION, map);
        assertEquals("Observation", map.get("mirth_type"));
        assertEquals(Boolean.FALSE, map.get(FhirSerializer.VALID_VARIABLE));
        assertEquals(1, map.get(FhirSerializer.ISSUE_COUNT_VARIABLE));
        assertEquals("error Observation: " + REFUSED_OBSERVATION, map.get(FhirSerializer.ISSUES_VARIABLE));
        assertTrue(map.get(FhirSerializer.OPERATION_OUTCOME_VARIABLE).toString().contains(REFUSED_OBSERVATION));
        assertTrue(s.toXML(OBSERVATION).startsWith("<Observation>"));
    }

    @Test
    public void checksTheTypeAlsoWithoutValidation() throws Exception {
        FhirSerializationProperties p = properties("Patient");
        p.setValidateInbound(false);
        FhirSerializer s = serializer(p);
        assertTrue("the engine must call the serializer", s.isSerializationRequired(true));
        assertTrue(rejection(s, OBSERVATION).contains(REFUSED_OBSERVATION));

        // An allowed type is not validated: no validation variables.
        Map<String, Object> map = new HashMap<>();
        s.populateMetaData(INVALID_PATIENT, map);
        assertNull(map.get(FhirSerializer.VALID_VARIABLE));
        assertTrue(s.toXML(INVALID_PATIENT).startsWith("<Patient>"));

        FhirSerializationProperties neither = new FhirSerializationProperties();
        neither.setValidateInbound(false);
        assertFalse(serializer(neither).isSerializationRequired(true));
    }

    @Test
    public void autoResponseIs400ForARefusedType() throws Exception {
        FhirSerializationProperties p = properties("Patient");
        ConnectorMessage cm = new ConnectorMessage("c1", "FHIR in", 1L, 0, "s1", Calendar.getInstance(), Status.RECEIVED);
        // "Before processing": the responder runs the data type's check itself.
        Response r = new FhirAutoResponder(p).getResponse(Status.ERROR, OBSERVATION, cm);
        assertEquals("400", cm.getChannelMap().get(FhirAutoResponder.HTTP_STATUS_VARIABLE));
        assertTrue(r.getMessage(), r.getMessage().contains(REFUSED_OBSERVATION));
    }

    // ------------------------------------------------------------------ saved channels

    @Test
    public void aChannelSavedWithoutThePropertyAcceptsEveryType() throws Exception {
        ObjectXMLSerializer xstream = ObjectXMLSerializer.getInstance();
        try {
            xstream.init("4.6.0");
        } catch (Exception e) {
            // another test in this JVM initialized it already
        }
        String saved = xstream.serialize(new FhirSerializationProperties()).replaceAll("\\s*<resourceTypes\\s*/>|\\s*<resourceTypes></resourceTypes>", "");
        assertFalse(saved, saved.contains("resourceTypes"));
        FhirSerializationProperties old = xstream.deserialize(saved, FhirSerializationProperties.class);
        assertEquals("", old.getResourceTypes());
        assertEquals(0, old.getPurgedProperties().get("resourceTypeCount"));

        FhirSerializer s = serializer(old);
        assertTrue(s.toXML(OBSERVATION).startsWith("<Observation>"));
        assertTrue(s.toXML(ENCOUNTER_XML).startsWith("<Encounter>"));
    }

    @Test
    public void isNotPartOfTheValidatorCacheKey() {
        // Same validator for every list: the list does not change what the validator loads.
        assertEquals(new FhirSerializationProperties().engineOptions(), properties("Patient").engineOptions());
    }
}
