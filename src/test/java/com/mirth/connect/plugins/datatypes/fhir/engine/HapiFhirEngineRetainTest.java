package com.mirth.connect.plugins.datatypes.fhir.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.UnknownCodeSystems;

import ca.uhn.fhir.context.FhirVersionEnum;

/** Dropping validators that are no longer used, and the specification of their FHIR version. */
public class HapiFhirEngineRetainTest {

    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"gender\":\"male\"}";

    private static final long ONE_HOUR = 60 * 60 * 1000L;

    private static Map<String, String> options(FhirVersion version, UnknownCodeSystems unknown) {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setFhirVersion(version);
        p.setUnknownCodeSystems(unknown);
        return p.engineOptions();
    }

    @Test
    public void dropsUnusedValidatorsAndTheSpecificationOfTheirVersion() throws Exception {
        HapiFhirEngine engine = new HapiFhirEngine();
        Map<String, String> r4 = options(FhirVersion.R4, UnknownCodeSystems.Warning);
        Map<String, String> r4Error = options(FhirVersion.R4, UnknownCodeSystems.Error);
        Map<String, String> r5 = options(FhirVersion.R5, UnknownCodeSystems.Warning);
        engine.validate(PATIENT, r4);
        engine.validate(PATIENT, r4Error);
        engine.validate(PATIENT, r5);
        assertEquals(Set.of(FhirVersionEnum.R4, FhirVersionEnum.R5), engine.loadedVersions());
        assertTrue(engine.loadedSpecification(FhirVersionEnum.R5) != null);
        // The engine's own lazy specification, never HAPI's static full copy.
        assertFalse(HapiFhirEngine.hapiStaticSpecifications().containsKey(FhirVersionEnum.R4));
        assertFalse(HapiFhirEngine.hapiStaticSpecifications().containsKey(FhirVersionEnum.R5));

        // Recently used validators stay, in use or not.
        engine.retainValidators(List.of(), ONE_HOUR);
        assertEquals(3, engine.validatorKeys().size());

        engine.retainValidators(List.of(r4), 0);
        assertEquals(Set.of(HapiFhirEngine.key(r4)), engine.validatorKeys());
        assertEquals(Set.of(FhirVersionEnum.R4), engine.loadedVersions());
        assertEquals("the R5 specification is released", null, engine.loadedSpecification(FhirVersionEnum.R5));
        assertTrue(engine.loadedSpecification(FhirVersionEnum.R4) != null);

        // Built again on demand.
        assertTrue(engine.validate("{\"resourceType\":\"Patient\",\"gender\":\"x\"}", r5).size() > 0);
        assertEquals(Set.of(FhirVersionEnum.R4, FhirVersionEnum.R5), engine.loadedVersions());

        engine.retainValidators(List.of(), 0);
        assertTrue(engine.validatorKeys().isEmpty());
        assertTrue(engine.loadedVersions().isEmpty());
    }

    @Test
    public void theRequiredProfileDoesNotMakeAnotherValidator() {
        Map<String, String> plain = options(FhirVersion.R4, UnknownCodeSystems.Warning);
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setRequiredProfile("http://example.org/fhir/StructureDefinition/x");
        assertEquals(HapiFhirEngine.key(plain), HapiFhirEngine.key(p.engineOptions()));
    }

    /** Fails when a HAPI FHIR update renames the static map, so the specification would never be released. */
    @Test
    public void hapiStillKeepsTheSpecificationsWhereTheEngineExpectsThem() throws Exception {
        assertTrue(HapiFhirEngine.hapiStaticSpecifications() instanceof Map);
    }
}
