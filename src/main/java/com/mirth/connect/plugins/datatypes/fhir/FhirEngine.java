/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.List;
import java.util.Map;

/**
 * What the data type needs from HAPI FHIR. Implemented in the isolated engine (see
 * {@link FhirEngineLoader}); only JDK types cross this boundary.
 */
public interface FhirEngine {

    /** Option keys; all values are strings. */
    String FHIR_VERSION = "fhirVersion";
    /** Files and folders with profiles: StructureDefinition/ValueSet/CodeSystem files and NPM packages (.tgz), separated by ';'. */
    String PROFILES = "profiles";
    /** Canonical URL of a profile every resource must conform to, in addition to its meta.profile. */
    String REQUIRED_PROFILE = "requiredProfile";
    /** Severity for codes from code systems the validator does not know: error, warning or information. */
    String UNKNOWN_CODE_SYSTEMS = "unknownCodeSystems";
    /** "true" or "false": whether extensions without a known definition are accepted. */
    String ANY_EXTENSIONS_ALLOWED = "anyExtensionsAllowed";
    /** Base URL of a FHIR terminology server for codes the validator cannot check itself, or empty. */
    String TERMINOLOGY_SERVER = "terminologyServer";

    /** Issue keys. */
    String SEVERITY = "severity";
    String LOCATION = "location";
    String LINE = "line";
    String COLUMN = "column";
    String MESSAGE = "message";
    String MESSAGE_ID = "messageId";

    /**
     * Validates a resource (JSON or XML). Returns the issues, each a map with severity (fatal, error,
     * warning or information), location, line, column, message and messageId.
     */
    List<Map<String, String>> validate(String resource, Map<String, String> options) throws Exception;

    /** FHIR XML (with namespace) to FHIR JSON. Fails on elements that are not in the specification. */
    String toJson(String xml, String fhirVersion, boolean pretty) throws Exception;

    /** FHIR JSON to FHIR XML in the order of the specification. Fails on properties that are not in the specification. */
    String toXml(String json, String fhirVersion, boolean pretty) throws Exception;

    /** FHIR XML (with namespace) written again in the order of the specification. Fails on elements that are not in the specification. */
    String reorderXml(String xml, String fhirVersion, boolean pretty) throws Exception;

    /** FHIR JSON written again in the order of the specification. Fails on properties that are not in the specification. */
    String reorderJson(String json, String fhirVersion, boolean pretty) throws Exception;

    /** Builds the validator for these options ahead of the first message; loading the specification takes a few seconds. */
    void warmUp(Map<String, String> options);

    /**
     * Drops the validators whose options are not in {@code inUse} and that were not used for
     * {@code idleMillis}, and the specification of a FHIR version once no validator needs it.
     */
    void retainValidators(List<Map<String, String>> inUse, long idleMillis);
}
