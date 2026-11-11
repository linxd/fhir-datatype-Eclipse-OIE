/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mirth.connect.donkey.model.message.SerializationType;
import com.mirth.connect.donkey.util.DonkeyElement;
import com.mirth.connect.model.datatype.DataTypePropertyDescriptor;
import com.mirth.connect.model.datatype.PropertyEditorType;
import com.mirth.connect.model.datatype.SerializationProperties;

/**
 * Settings of the FHIR data type.
 *
 * The engine's XStream deserializer does not run the constructor, so a property added in a later
 * version is null in a saved channel. The getters fall back to the defaults, which is why the
 * properties that default to true are Booleans.
 */
public class FhirSerializationProperties extends SerializationProperties {

    /** R4 (4.0.1), R4B (4.3.0) and R5 (5.0.0). Channels saved before R4B and R5 were added keep R4. */
    public enum FhirVersion {
        R4, R4B, R5
    }

    public enum TransformerFormat {
        XML, JSON
    }

    public enum OutputFormat {
        JSON, XML
    }

    public enum InvalidMessages {
        Reject, Accept
    }

    public enum FailOn {
        Errors, Warnings
    }

    public enum UnknownCodeSystems {
        Warning, Error, Information
    }

    private FhirVersion fhirVersion = FhirVersion.R4;
    private String resourceTypes = "";
    private TransformerFormat transformerFormat = TransformerFormat.XML;
    private Boolean stripNamespaces = Boolean.TRUE;
    private OutputFormat outputFormat = OutputFormat.JSON;
    private Boolean prettyPrint = Boolean.TRUE;
    private Boolean validateInbound = Boolean.TRUE;
    private boolean validateOutbound = false;
    private InvalidMessages invalidMessages = InvalidMessages.Reject;
    private FailOn failOn = FailOn.Errors;
    private String profiles = "";
    private String requiredProfile = "";
    private UnknownCodeSystems unknownCodeSystems = UnknownCodeSystems.Warning;
    private Boolean anyExtensionsAllowed = Boolean.TRUE;
    private String terminologyServer = "";

    public FhirSerializationProperties() {}

    @Override
    public Map<String, DataTypePropertyDescriptor> getPropertyDescriptors() {
        Map<String, DataTypePropertyDescriptor> p = new LinkedHashMap<>();
        p.put("fhirVersion", new DataTypePropertyDescriptor(getFhirVersion(), "FHIR Version", "The FHIR version of the messages: R4 (4.0.1), R4B (4.3.0) or R5 (5.0.0). Used for validation and for converting between JSON and XML. Profile packages must be for the same version.", PropertyEditorType.OPTION, FhirVersion.values()));
        p.put("resourceTypes", new DataTypePropertyDescriptor(getResourceTypes(), "Resource Types", "The resource types this connector accepts, separated by commas, for example Patient, Observation, Bundle. A message of another type, or a Bundle with an entry of another type, is invalid: Invalid Messages decides whether it is rejected or accepted. A Bundle needs the types of its entries in the list too. Names are case-sensitive. Leave empty to accept every type. This is a check on the input; it does not make the validator use less memory.", PropertyEditorType.STRING));
        p.put("transformerFormat", new DataTypePropertyDescriptor(getTransformerFormat(), "Transformer Format", "XML: the transformer works on FHIR XML with E4X (msg.name.family.@value). JSON: msg is a JavaScript object of the FHIR JSON (msg.name[0].family), and the outbound message is made from it. With JSON, decimals lose trailing zeros (1.50 becomes 1.5), as in any JavaScript. On outbound properties it sets the format of the outbound template (tmp).", PropertyEditorType.OPTION, TransformerFormat.values()));
        p.put("stripNamespaces", new DataTypePropertyDescriptor(isStripNamespaces(), "Strip Namespaces", "If checked, the FHIR namespace (http://hl7.org/fhir) is removed from the XML the transformer works on, so you can write msg.name.family.@value instead of using namespaces. It is put back when the message leaves the transformer. The XHTML namespace of narratives (text.div) is kept.", PropertyEditorType.BOOLEAN));
        p.put("outputFormat", new DataTypePropertyDescriptor(getOutputFormat(), "Output Format", "The format of the outbound message (the encoded data): FHIR JSON or FHIR XML. Messages come in as either; the transformer always works on FHIR XML. After a transformer the FHIR engine writes the message in the element order of the specification.", PropertyEditorType.OPTION, OutputFormat.values()));
        p.put("prettyPrint", new DataTypePropertyDescriptor(isPrettyPrint(), "Pretty Print", "If checked, outbound JSON and XML created by the FHIR engine are indented.", PropertyEditorType.BOOLEAN));
        p.put("validateInbound", new DataTypePropertyDescriptor(isValidateInbound(), "Validate Inbound", "If checked, every inbound message is validated against the FHIR specification and the profiles below: structure, data types, cardinality, required elements, value set bindings and invariants.", PropertyEditorType.BOOLEAN));
        p.put("validateOutbound", new DataTypePropertyDescriptor(isValidateOutbound(), "Validate Outbound", "If checked, the outbound message is validated too, after the transformer. An invalid outbound message is always rejected.", PropertyEditorType.BOOLEAN));
        p.put("invalidMessages", new DataTypePropertyDescriptor(getInvalidMessages(), "Invalid Messages", "Reject: an invalid inbound message gets status ERROR, with the validation issues as the error. Accept: it is processed normally, and the connector map gets fhirValid (true/false), fhirIssueCount and fhirIssues (the issues as text) for your filter or transformer.", PropertyEditorType.OPTION, InvalidMessages.values()));
        p.put("failOn", new DataTypePropertyDescriptor(getFailOn(), "Invalid When", "Errors: a message is invalid when validation finds errors. Warnings: warnings make it invalid too.", PropertyEditorType.OPTION, FailOn.values()));
        p.put("profiles", new DataTypePropertyDescriptor(getProfiles(), "Profile Packages", "Files or folders on the server with profiles to validate against, separated by ';'. A folder is read with its subfolders. Accepted: FHIR packages (.tgz, as downloaded from packages.fhir.org or simplifier.net, e.g. nictiz.fhir.nl.r4.zib2020 with its dependencies) and StructureDefinition, ValueSet and CodeSystem files (.json or .xml). A resource is validated against the profiles in its meta.profile.", PropertyEditorType.STRING));
        p.put("requiredProfile", new DataTypePropertyDescriptor(getRequiredProfile(), "Required Profile", "The canonical URL of a profile every message must conform to, whatever its meta.profile says, for example http://nictiz.nl/fhir/StructureDefinition/nl-core-Patient. Leave empty to only use meta.profile.", PropertyEditorType.STRING));
        p.put("unknownCodeSystems", new DataTypePropertyDescriptor(getUnknownCodeSystems(), "Unknown Code Systems", "How a code from a code system the validator does not know is reported (for example SNOMED CT or LOINC, which are not part of the specification). Warning is usual; use Error to require every code to be checked, together with a terminology server.", PropertyEditorType.OPTION, UnknownCodeSystems.values()));
        p.put("anyExtensionsAllowed", new DataTypePropertyDescriptor(isAnyExtensionsAllowed(), "Allow Unknown Extensions", "If checked, extensions whose definition the validator does not know are accepted. If not checked, they are errors.", PropertyEditorType.BOOLEAN));
        p.put("terminologyServer", new DataTypePropertyDescriptor(getTerminologyServer(), "Terminology Server", "Optional base URL of a FHIR terminology server (for example the Nationale Terminologieserver) to check codes the validator cannot check itself. Every such code is a call to the server. Leave empty to validate offline.", PropertyEditorType.STRING));
        return p;
    }

    @Override
    public void setProperties(Map<String, Object> properties) {
        if (properties == null) {
            return;
        }
        if (properties.get("fhirVersion") != null) {
            fhirVersion = toEnum(FhirVersion.class, properties.get("fhirVersion"));
        }
        if (properties.get("resourceTypes") != null) {
            resourceTypes = properties.get("resourceTypes").toString();
        }
        if (properties.get("transformerFormat") != null) {
            transformerFormat = toEnum(TransformerFormat.class, properties.get("transformerFormat"));
        }
        if (properties.get("stripNamespaces") != null) {
            stripNamespaces = toBoolean(properties.get("stripNamespaces"));
        }
        if (properties.get("outputFormat") != null) {
            outputFormat = toEnum(OutputFormat.class, properties.get("outputFormat"));
        }
        if (properties.get("prettyPrint") != null) {
            prettyPrint = toBoolean(properties.get("prettyPrint"));
        }
        if (properties.get("validateInbound") != null) {
            validateInbound = toBoolean(properties.get("validateInbound"));
        }
        if (properties.get("validateOutbound") != null) {
            validateOutbound = toBoolean(properties.get("validateOutbound"));
        }
        if (properties.get("invalidMessages") != null) {
            invalidMessages = toEnum(InvalidMessages.class, properties.get("invalidMessages"));
        }
        if (properties.get("failOn") != null) {
            failOn = toEnum(FailOn.class, properties.get("failOn"));
        }
        if (properties.get("profiles") != null) {
            profiles = properties.get("profiles").toString();
        }
        if (properties.get("requiredProfile") != null) {
            requiredProfile = properties.get("requiredProfile").toString();
        }
        if (properties.get("unknownCodeSystems") != null) {
            unknownCodeSystems = toEnum(UnknownCodeSystems.class, properties.get("unknownCodeSystems"));
        }
        if (properties.get("anyExtensionsAllowed") != null) {
            anyExtensionsAllowed = toBoolean(properties.get("anyExtensionsAllowed"));
        }
        if (properties.get("terminologyServer") != null) {
            terminologyServer = properties.get("terminologyServer").toString();
        }
    }

    /**
     * An option value as the enum. The Administrator passes the enum itself; the web admin's Web
     * Support plugin passes its name as text (it only converts Booleans and Integers back).
     */
    static <E extends Enum<E>> E toEnum(Class<E> type, Object value) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return Enum.valueOf(type, value.toString().trim());
    }

    /** A Boolean option, also from "true" / "false" text. */
    static Boolean toBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : Boolean.valueOf(value.toString().trim());
    }

    /** The options for {@link FhirEngine#validate}. */
    public Map<String, String> engineOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(FhirEngine.FHIR_VERSION, getFhirVersion().name());
        options.put(FhirEngine.PROFILES, getProfiles());
        options.put(FhirEngine.REQUIRED_PROFILE, getRequiredProfile());
        options.put(FhirEngine.UNKNOWN_CODE_SYSTEMS, getUnknownCodeSystems().name().toLowerCase());
        options.put(FhirEngine.ANY_EXTENSIONS_ALLOWED, String.valueOf(isAnyExtensionsAllowed()));
        options.put(FhirEngine.TERMINOLOGY_SERVER, getTerminologyServer());
        return options;
    }

    public FhirVersion getFhirVersion() {
        return fhirVersion == null ? FhirVersion.R4 : fhirVersion;
    }

    public void setFhirVersion(FhirVersion fhirVersion) {
        this.fhirVersion = fhirVersion;
    }

    public String getResourceTypes() {
        return resourceTypes == null ? "" : resourceTypes;
    }

    public void setResourceTypes(String resourceTypes) {
        this.resourceTypes = resourceTypes;
    }

    public TransformerFormat getTransformerFormat() {
        return transformerFormat == null ? TransformerFormat.XML : transformerFormat;
    }

    public void setTransformerFormat(TransformerFormat transformerFormat) {
        this.transformerFormat = transformerFormat;
    }

    /**
     * What msg is in the transformer. The engine asks the inbound properties for this (and the outbound
     * properties for the outbound template), so it can differ per connector; see DataTypeFactory.
     */
    @Override
    public SerializationType getSerializationType() {
        return getTransformerFormat() == TransformerFormat.JSON ? SerializationType.JSON : SerializationType.XML;
    }

    public boolean isStripNamespaces() {
        return stripNamespaces == null || stripNamespaces;
    }

    public void setStripNamespaces(boolean stripNamespaces) {
        this.stripNamespaces = stripNamespaces;
    }

    public OutputFormat getOutputFormat() {
        return outputFormat == null ? OutputFormat.JSON : outputFormat;
    }

    public void setOutputFormat(OutputFormat outputFormat) {
        this.outputFormat = outputFormat;
    }

    public boolean isPrettyPrint() {
        return prettyPrint == null || prettyPrint;
    }

    public void setPrettyPrint(boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
    }

    public boolean isValidateInbound() {
        return validateInbound == null || validateInbound;
    }

    public void setValidateInbound(boolean validateInbound) {
        this.validateInbound = validateInbound;
    }

    public boolean isValidateOutbound() {
        return validateOutbound;
    }

    public void setValidateOutbound(boolean validateOutbound) {
        this.validateOutbound = validateOutbound;
    }

    public InvalidMessages getInvalidMessages() {
        return invalidMessages == null ? InvalidMessages.Reject : invalidMessages;
    }

    public void setInvalidMessages(InvalidMessages invalidMessages) {
        this.invalidMessages = invalidMessages;
    }

    public FailOn getFailOn() {
        return failOn == null ? FailOn.Errors : failOn;
    }

    public void setFailOn(FailOn failOn) {
        this.failOn = failOn;
    }

    public String getProfiles() {
        return profiles == null ? "" : profiles;
    }

    public void setProfiles(String profiles) {
        this.profiles = profiles;
    }

    public String getRequiredProfile() {
        return requiredProfile == null ? "" : requiredProfile;
    }

    public void setRequiredProfile(String requiredProfile) {
        this.requiredProfile = requiredProfile;
    }

    public UnknownCodeSystems getUnknownCodeSystems() {
        return unknownCodeSystems == null ? UnknownCodeSystems.Warning : unknownCodeSystems;
    }

    public void setUnknownCodeSystems(UnknownCodeSystems unknownCodeSystems) {
        this.unknownCodeSystems = unknownCodeSystems;
    }

    public boolean isAnyExtensionsAllowed() {
        return anyExtensionsAllowed == null || anyExtensionsAllowed;
    }

    public void setAnyExtensionsAllowed(boolean anyExtensionsAllowed) {
        this.anyExtensionsAllowed = anyExtensionsAllowed;
    }

    public String getTerminologyServer() {
        return terminologyServer == null ? "" : terminologyServer;
    }

    public void setTerminologyServer(String terminologyServer) {
        this.terminologyServer = terminologyServer;
    }

    // @formatter:off
    @Override public void migrate3_0_1(DonkeyElement element) {}
    @Override public void migrate3_0_2(DonkeyElement element) {}
    @Override public void migrate3_1_0(DonkeyElement element) {}
    @Override public void migrate3_2_0(DonkeyElement element) {}
    @Override public void migrate3_3_0(DonkeyElement element) {}
    @Override public void migrate3_4_0(DonkeyElement element) {}
    @Override public void migrate3_5_0(DonkeyElement element) {}
    @Override public void migrate3_6_0(DonkeyElement element) {}
    @Override public void migrate3_7_0(DonkeyElement element) {}
    @Override public void migrate3_9_0(DonkeyElement element) {}
    @Override public void migrate3_11_0(DonkeyElement element) {}
    @Override public void migrate3_11_1(DonkeyElement element) {}
    @Override public void migrate3_12_0(DonkeyElement element) {}
    // @formatter:on

    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new HashMap<>();
        purged.put("fhirVersion", getFhirVersion());
        purged.put("resourceTypeCount", FhirResourceTypes.parse(getResourceTypes()).size());
        purged.put("transformerFormat", getTransformerFormat());
        purged.put("stripNamespaces", isStripNamespaces());
        purged.put("outputFormat", getOutputFormat());
        purged.put("prettyPrint", isPrettyPrint());
        purged.put("validateInbound", isValidateInbound());
        purged.put("validateOutbound", isValidateOutbound());
        purged.put("invalidMessages", getInvalidMessages());
        purged.put("failOn", getFailOn());
        purged.put("profilesSet", !getProfiles().isEmpty());
        purged.put("requiredProfileSet", !getRequiredProfile().isEmpty());
        purged.put("unknownCodeSystems", getUnknownCodeSystems());
        purged.put("anyExtensionsAllowed", isAnyExtensionsAllowed());
        purged.put("terminologyServerSet", !getTerminologyServer().isEmpty());
        return purged;
    }
}
