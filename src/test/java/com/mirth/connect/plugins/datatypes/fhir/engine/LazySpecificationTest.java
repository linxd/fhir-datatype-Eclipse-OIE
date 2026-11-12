package com.mirth.connect.plugins.datatypes.fhir.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.UnknownCodeSystemWarningValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.junit.AfterClass;
import org.junit.Test;

import com.mirth.connect.plugins.datatypes.fhir.FhirEngine;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;

/** The lazy specification gives the same issues as HAPI's DefaultProfileValidationSupport (issue #26). */
public class LazySpecificationTest {

    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"http://fhir.nl/fhir/NamingSystem/bsn\",\"value\":\"999911120\"}],"
            + "\"name\":[{\"family\":\"Jansen\",\"given\":[\"Piet\"]}],\"gender\":\"male\",\"birthDate\":\"1980-01-01\",\"telecom\":[{\"system\":\"phone\",\"value\":\"0612345678\"}]}";

    private static final String OBSERVATION = "{\"resourceType\":\"Observation\",\"status\":\"final\",\"category\":[{\"coding\":[{\"system\":\"http://terminology.hl7.org/CodeSystem/observation-category\",\"code\":\"vital-signs\"}]}],"
            + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"29463-7\"}]},\"subject\":{\"reference\":\"Patient/p1\"},\"effectiveDateTime\":\"2026-10-01\","
            + "\"valueQuantity\":{\"value\":72.5,\"unit\":\"kg\",\"system\":\"http://unitsofmeasure.org\",\"code\":\"kg\"}}";

    private static final String BIRTH_PLACE = "\"extension\":[{\"url\":\"http://hl7.org/fhir/StructureDefinition/patient-birthPlace\",\"valueAddress\":{\"city\":\"Utrecht\"}}],";

    /** Valid and invalid messages that touch value sets, code systems, invariants and extensions. */
    private static Map<String, String> messages() throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("patient", PATIENT);
        m.put("observation", OBSERVATION);
        m.put("gender x", PATIENT.replace("\"male\"", "\"x\""));
        m.put("telecom system x", PATIENT.replace("\"phone\"", "\"x\""));
        m.put("telecom without system", PATIENT.replace("{\"system\":\"phone\",", "{"));
        m.put("status done", OBSERVATION.replace("\"final\"", "\"done\""));
        m.put("unknown category", OBSERVATION.replace("vital-signs", "no-such-category"));
        m.put("ucum unit", OBSERVATION.replace("\"code\":\"kg\"", "\"code\":\"kilo\""));
        m.put("birthPlace", PATIENT.replace("{\"resourceType\":\"Patient\",", "{\"resourceType\":\"Patient\"," + BIRTH_PLACE));
        m.put("birthPlace as string", PATIENT.replace("{\"resourceType\":\"Patient\",", "{\"resourceType\":\"Patient\","
                + BIRTH_PLACE.replace("\"valueAddress\":{\"city\":\"Utrecht\"}", "\"valueString\":\"Utrecht\"")));
        for (String f : new String[] { "patient.json", "patient-invalid.json", "observation-bundle.json" }) {
            m.put(f, read(new File("examples", f)));
        }
        File[] r6 = new File("examples/build.fhir.org-R6/json").listFiles((d, n) -> n.endsWith(".json"));
        Arrays.sort(r6);
        for (File f : r6) {
            m.put(f.getName(), read(f));
        }
        return m;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, String> options(FhirVersion version) {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setFhirVersion(version);
        return p.engineOptions();
    }

    /** HAPI's own default, set up like HapiFhirEngine.build. */
    private static FhirValidator hapiDefault(FhirContext ctx) {
        List<IValidationSupport> supports = new ArrayList<>();
        supports.add(new DefaultProfileValidationSupport(ctx));
        supports.add(new CommonCodeSystemsTerminologyService(ctx));
        supports.add(new InMemoryTerminologyServerValidationSupport(ctx));
        supports.add(new SnapshotGeneratingValidationSupport(ctx));
        UnknownCodeSystemWarningValidationSupport unknown = new UnknownCodeSystemWarningValidationSupport(ctx);
        unknown.setNonExistentCodeSystemSeverity(IValidationSupport.IssueSeverity.WARNING);
        supports.add(unknown);
        FhirInstanceValidator module = new FhirInstanceValidator(new ValidationSupportChain(supports));
        module.setAnyExtensionsAllowed(true);
        module.setErrorForUnknownProfiles(true);
        module.setBestPracticeWarningLevel(BestPracticeWarningLevel.Ignore);
        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(module);
        return validator;
    }

    private static String issues(List<Map<String, String>> issues) {
        return issues.stream().map(i -> i.get(FhirEngine.SEVERITY) + " " + i.get(FhirEngine.LOCATION) + " " + i.get(FhirEngine.MESSAGE)).sorted().collect(Collectors.joining("\n"));
    }

    private static String issues(ca.uhn.fhir.validation.ValidationResult result) {
        return result.getMessages().stream().map(m -> m.getSeverity().getCode() + " " + m.getLocationString() + " " + m.getMessage()).sorted().collect(Collectors.joining("\n"));
    }

    private static void sameAsHapi(FhirVersion version, FhirVersionEnum hapiVersion) throws Exception {
        HapiFhirEngine engine = new HapiFhirEngine();
        Map<String, String> options = options(version);
        FhirValidator reference = hapiDefault(FhirContext.forVersion(hapiVersion));
        List<String> differences = new ArrayList<>();
        int invalid = 0;
        Map<String, String> messages = messages();
        for (Map.Entry<String, String> m : messages.entrySet()) {
            String expected = issues(reference.validateWithResult(m.getValue()));
            String actual = issues(engine.validate(m.getValue(), options));
            if (expected.contains("error ")) {
                invalid++;
            }
            if (!expected.equals(actual)) {
                differences.add(m.getKey() + "\n  HAPI default:\n    " + expected.replace("\n", "\n    ") + "\n  lazy:\n    " + actual.replace("\n", "\n    "));
            }
        }
        assertEquals(version + ": " + String.join("\n", differences), 0, differences.size());
        assertTrue("the set must contain invalid messages, got " + invalid, invalid >= 5);
        LazySpecificationSupport spec = engine.loadedSpecification(hapiVersion);
        assertTrue(spec.terminologyCount() > 1000);
        assertTrue("only what the messages need is parsed: " + spec.parsedCount(), spec.parsedCount() < spec.terminologyCount() / 10);
    }

    @Test
    public void r4GivesTheSameIssuesAsHapi() throws Exception {
        sameAsHapi(FhirVersion.R4, FhirVersionEnum.R4);
    }

    @Test
    public void r4bGivesTheSameIssuesAsHapi() throws Exception {
        sameAsHapi(FhirVersion.R4B, FhirVersionEnum.R4B);
    }

    @Test
    public void r5GivesTheSameIssuesAsHapi() throws Exception {
        sameAsHapi(FhirVersion.R5, FhirVersionEnum.R5);
    }

    @Test
    public void theEngineNeverFillsHapisStaticCopy() throws Exception {
        HapiFhirEngine.releaseSpecification(FhirVersionEnum.R5);
        HapiFhirEngine engine = new HapiFhirEngine();
        engine.validate(OBSERVATION, options(FhirVersion.R5));
        assertFalse(HapiFhirEngine.hapiStaticSpecifications().containsKey(FhirVersionEnum.R5));
    }

    /** The reference validators above fill HAPI's static copy; leave nothing for the other tests. */
    @AfterClass
    public static void releaseHapiCopies() {
        for (FhirVersionEnum v : new FhirVersionEnum[] { FhirVersionEnum.R4, FhirVersionEnum.R4B, FhirVersionEnum.R5 }) {
            HapiFhirEngine.releaseSpecification(v);
        }
    }
}
