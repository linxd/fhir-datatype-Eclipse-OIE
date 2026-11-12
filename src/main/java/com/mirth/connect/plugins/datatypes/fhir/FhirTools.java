/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FailOn;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;

/**
 * FHIR functions for channel scripts, for example to validate a response or build JSON in a
 * transformer:
 *
 * <pre>
 * var FhirTools = Packages.com.mirth.connect.plugins.datatypes.fhir.FhirTools;
 * var result = FhirTools.validate(responseJson);
 * if (!result.isValid()) logger.warn(result.summary());
 * </pre>
 *
 * Runs on the server only.
 */
public final class FhirTools {

    private FhirTools() {}

    /**
     * Validates a FHIR JSON or XML resource against the base specification and its meta.profile.
     * XML may be without the FHIR namespace, e.g. msg.toString() in a transformer.
     */
    public static FhirValidation validate(String resource) throws Exception {
        return validate(resource, "", "");
    }

    /** Validates against a profile, which must be known (see the next method for your own profiles). */
    public static FhirValidation validate(String resource, String requiredProfile) throws Exception {
        return validate(resource, requiredProfile, "");
    }

    /**
     * @param requiredProfile canonical URL of a profile the resource must conform to, or empty
     * @param profiles files and folders with profiles and packages, separated by ';', or empty
     */
    public static FhirValidation validate(String resource, String requiredProfile, String profiles) throws Exception {
        return validate(resource, requiredProfile, profiles, "R4");
    }

    /**
     * @param fhirVersion R4, R4B or R5; the profiles must be for the same version
     */
    public static FhirValidation validate(String resource, String requiredProfile, String profiles, String fhirVersion) throws Exception {
        FhirSerializationProperties properties = new FhirSerializationProperties();
        properties.setFhirVersion(version(fhirVersion));
        properties.setRequiredProfile(requiredProfile == null ? "" : requiredProfile);
        properties.setProfiles(profiles == null ? "" : profiles);
        String source = resource.trim();
        if (FhirXml.detect(source) == FhirXml.Format.XML) {
            source = FhirXml.addNamespace(source);
        }
        FhirEngine engine = FhirEngineLoader.engine();
        return new FhirValidation(engine.validate(source, properties.engineOptions()), FailOn.Errors);
    }

    /** FHIR R4 XML (with or without the FHIR namespace, e.g. msg.toString()) to FHIR JSON. */
    public static String toJson(String xml) throws Exception {
        return toJson(xml, "R4");
    }

    /** As {@link #toJson(String)}, for FHIR version R4, R4B or R5. */
    public static String toJson(String xml, String fhirVersion) throws Exception {
        FhirXml.detect(xml); // refuses FHIR XML with a DOCTYPE
        return FhirEngineLoader.engine().toJson(FhirXml.addNamespace(xml.trim()), version(fhirVersion).name(), true);
    }

    /** FHIR R4 JSON to FHIR XML, with the FHIR namespace. */
    public static String toXml(String json) throws Exception {
        return toXml(json, "R4");
    }

    /** As {@link #toXml(String)}, for FHIR version R4, R4B or R5. */
    public static String toXml(String json, String fhirVersion) throws Exception {
        FhirXml.detect(json); // refuses a JavaScript object passed as msg
        return FhirEngineLoader.engine().toXml(json, version(fhirVersion).name(), true);
    }

    private static FhirVersion version(String fhirVersion) {
        String name = fhirVersion == null ? "" : fhirVersion.trim().toUpperCase();
        if (name.isEmpty()) {
            return FhirVersion.R4;
        }
        try {
            return FhirVersion.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("FHIR version " + fhirVersion + " is not supported. Use R4, R4B or R5.");
        }
    }
}
