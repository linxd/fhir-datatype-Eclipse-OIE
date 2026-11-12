package com.mirth.connect.plugins.datatypes.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.mirth.connect.donkey.model.message.MessageSerializerException;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.OutputFormat;

/** FHIR versions R4B and R5 next to R4 (issue #10). */
public class FhirVersionTest {

    /** Observation.triggeredBy is new in R5. */
    private static final String R5_OBSERVATION = "{\"resourceType\":\"Observation\",\"id\":\"o2\",\"status\":\"final\",\"code\":{\"text\":\"Glucose\"},"
            + "\"triggeredBy\":[{\"observation\":{\"reference\":\"Observation/o1\"},\"type\":\"reflex\"}]}";

    /** NutritionProduct is new in R4B (and also in R5). */
    private static final String R4B_NUTRITION_PRODUCT = "{\"resourceType\":\"NutritionProduct\",\"id\":\"n1\",\"status\":\"active\"}";

    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"gender\":\"female\",\"birthDate\":\"1980-01-01\"}";

    private static final String PROFILE_URL = "http://example.org/fhir/StructureDefinition/patient-with-birthdate";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass
    public static void directEngine() {
        System.setProperty("fhir.engine.direct", "true");
    }

    private static FhirSerializationProperties properties(FhirVersion version) {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setFhirVersion(version);
        return p;
    }

    private static FhirSerializer serializer(FhirSerializationProperties p) {
        return new FhirSerializer(new SerializerProperties(p, null, null));
    }

    /** toXML after populateMetaData, as the engine calls them; returns the error text or null. */
    private static String inboundError(FhirSerializationProperties p, String message, Map<String, Object> map) {
        FhirSerializer s = serializer(p);
        s.populateMetaData(message, map);
        try {
            s.toXML(message);
            return null;
        } catch (MessageSerializerException e) {
            return messages(e);
        }
    }

    private static String messages(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    public void offersR4R4bAndR5AndKeepsR4ForSavedChannels() {
        assertEquals("[R4, R4B, R5]", java.util.Arrays.toString(FhirVersion.values()));
        FhirSerializationProperties saved = new FhirSerializationProperties();
        saved.setFhirVersion(null); // XStream leaves fields of older channels null
        assertEquals(FhirVersion.R4, saved.getFhirVersion());
        assertEquals("R4", saved.engineOptions().get(FhirEngine.FHIR_VERSION));
    }

    @Test
    public void r5ChannelAcceptsAnR5OnlyElement() {
        Map<String, Object> map = new HashMap<>();
        String error = inboundError(properties(FhirVersion.R5), R5_OBSERVATION, map);
        assertEquals(null, error);
        assertEquals("R5", map.get("mirth_version"));
        assertEquals(map.get(FhirSerializer.ISSUES_VARIABLE).toString(), Boolean.TRUE, map.get(FhirSerializer.VALID_VARIABLE));
    }

    @Test
    public void r4ChannelRejectsTheR5OnlyElement() {
        String error = inboundError(properties(FhirVersion.R4), R5_OBSERVATION, new HashMap<>());
        assertTrue(String.valueOf(error), error != null && error.contains("triggeredBy"));
    }

    @Test
    public void r4bChannelAcceptsAnR4bResourceThatR4DoesNotKnow() {
        assertEquals(null, inboundError(properties(FhirVersion.R4B), R4B_NUTRITION_PRODUCT, new HashMap<>()));
        assertTrue(inboundError(properties(FhirVersion.R4), R4B_NUTRITION_PRODUCT, new HashMap<>()) != null);
    }

    @Test
    public void convertsR5BetweenJsonAndXml() throws Exception {
        FhirSerializationProperties p = properties(FhirVersion.R5);
        p.setOutputFormat(OutputFormat.XML);
        FhirSerializer s = serializer(p);
        s.populateMetaData(R5_OBSERVATION, new HashMap<>());
        String xml = s.transformWithoutSerializing(R5_OBSERVATION, s);
        assertTrue(xml, xml.contains("<triggeredBy>"));

        // Through a transformer: XML back to JSON with the R5 model.
        FhirSerializationProperties json = properties(FhirVersion.R5);
        FhirSerializer out = serializer(json);
        String transformerXml = out.toXML(R5_OBSERVATION);
        String result = out.fromXML(transformerXml);
        assertTrue(result, result.contains("\"triggeredBy\""));
    }

    @Test
    public void toolsTakeTheVersion() throws Exception {
        assertTrue(FhirTools.validate(R5_OBSERVATION, "", "", "R5").summary(), FhirTools.validate(R5_OBSERVATION, "", "", "R5").isValid());
        assertFalse(FhirTools.validate(R5_OBSERVATION).isValid());
        String xml = FhirTools.toXml(R5_OBSERVATION, "r5");
        assertTrue(xml, xml.contains("<triggeredBy>"));
        assertTrue(FhirTools.toJson(xml, "R5").contains("triggeredBy"));
        try {
            FhirTools.toXml(R5_OBSERVATION, "R6");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("FHIR version R6 is not supported. Use R4, R4B or R5.", e.getMessage());
        }
    }

    @Test
    public void refusesAnR4ProfileFileOnAnR5Channel() {
        FhirSerializationProperties p = properties(FhirVersion.R5);
        p.setProfiles(new File("src/test/resources/profiles").getAbsolutePath());
        String error = inboundError(p, PATIENT, new HashMap<>());
        assertTrue(String.valueOf(error), error != null
                && error.contains("StructureDefinition patient-with-birthdate.json is for FHIR 4.0.1, but FHIR Version is R5 (5.0.0)"));
    }

    @Test
    public void refusesAPackageForAnotherVersion() throws Exception {
        FhirSerializationProperties p = properties(FhirVersion.R5);
        p.setProfiles(npmPackage("example.r4", "4.0.1").getAbsolutePath());
        String error = inboundError(p, PATIENT, new HashMap<>());
        assertTrue(String.valueOf(error), error != null && error.contains("Package example.r4#1.0.0 is for FHIR 4.0.1, but FHIR Version is R5"));
    }

    @Test
    public void loadsAPackageForTheChannelsVersion() throws Exception {
        FhirSerializationProperties p = properties(FhirVersion.R5);
        p.setProfiles(npmPackage("example.r5", "5.0.0").getAbsolutePath());
        p.setRequiredProfile(PROFILE_URL);
        assertEquals(null, inboundError(p, PATIENT, new HashMap<>()));
        String error = inboundError(p, "{\"resourceType\":\"Patient\",\"gender\":\"female\"}", new HashMap<>());
        assertTrue(String.valueOf(error), error != null && error.contains("Patient.birthDate"));
    }

    /** A minimal NPM package with the test profile, declared for the given FHIR version. */
    private File npmPackage(String name, String fhirVersion) throws Exception {
        String profile = new String(Files.readAllBytes(new File("src/test/resources/profiles/patient-with-birthdate.json").toPath()), StandardCharsets.UTF_8)
                .replace("\"4.0.1\"", "\"" + fhirVersion + "\"");
        String packageJson = "{\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"fhirVersions\":[\"" + fhirVersion + "\"]}";
        File file = new File(temp.getRoot(), name + ".tgz");
        try (OutputStream out = new FileOutputStream(file); TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(out))) {
            add(tar, "package/package.json", packageJson);
            add(tar, "package/StructureDefinition-patient-with-birthdate.json", profile);
        }
        return file;
    }

    private static void add(TarArchiveOutputStream tar, String path, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(path);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }
}
