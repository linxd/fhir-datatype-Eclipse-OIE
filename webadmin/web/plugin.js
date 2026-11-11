// FHIR data type - properties panel for the web administrator (equivalent of the Swing
// DataTypeClientPlugin properties). Same shape as the built-in data type plugins.
const PKG = "com.mirth.connect.plugins.datatypes.fhir";

const bool = (key, label, def, hint) => ({ key, label, type: "checkbox", default: def, hint });
const text = (key, label, def, hint) => ({ key, label, type: "text", default: def, hint });
const opt = (key, label, values, def, hint) => ({ key, label, type: "select", options: values.map((value) => ({ value, label: value })), default: def, hint });
const code = (key, label, def, hint) => ({ key, label, type: "code", default: def, hint });

const BATCH_SCRIPT_HINT =
  "JavaScript that splits the batch and returns the next message. Has access to 'reader' (a Java BufferedReader); return null/empty to signal end of input. Only used when Process Batch is enabled in the connector.";

const DEF = {
  name: "FHIR",
  label: "FHIR",
  order: 73,
  propertiesClass: `${PKG}.FhirDataTypeProperties`,
  groups: [
    {
      key: "serializationProperties",
      label: "Serialization",
      class: `${PKG}.FhirSerializationProperties`,
      fields: [
        opt("fhirVersion", "FHIR Version", ["R4", "R4B", "R5"], "R4", "The FHIR version of the messages: R4 (4.0.1), R4B (4.3.0) or R5 (5.0.0). Used for validation and for converting between JSON and XML. Profile packages must be for the same version."),
        text("resourceTypes", "Resource Types", "", "The resource types this connector accepts, separated by commas, for example Patient, Observation, Bundle. A message of another type, or a Bundle with an entry of another type, is invalid: Invalid Messages decides whether it is rejected or accepted. Names are case-sensitive. Leave empty to accept every type. It does not make the validator use less memory."),
        opt("transformerFormat", "Transformer Format", ["XML", "JSON"], "XML", "XML: the transformer works on FHIR XML with E4X (msg.name.family.@value). JSON: msg is a JavaScript object of the FHIR JSON (msg.name[0].family), and the outbound message is made from it. With JSON, decimals lose trailing zeros (1.50 becomes 1.5). On outbound properties it sets the format of the outbound template (tmp)."),
        bool("stripNamespaces", "Strip Namespaces", true, "If checked, the FHIR namespace (http://hl7.org/fhir) is removed from the XML the transformer works on, so you can write msg.name.family.@value. It is put back when the message leaves the transformer. The XHTML namespace of narratives (text.div) is kept."),
        opt("outputFormat", "Output Format", ["JSON", "XML"], "JSON", "The format of the outbound message (the encoded data): FHIR JSON or FHIR XML. Messages come in as either; the transformer always works on FHIR XML. After a transformer the FHIR engine writes the message in the element order of the specification."),
        bool("prettyPrint", "Pretty Print", true, "If checked, outbound JSON and XML created by the FHIR engine are indented."),
        bool("validateInbound", "Validate Inbound", true, "If checked, every inbound message is validated against the FHIR specification and the profiles below: structure, data types, cardinality, required elements, value set bindings and invariants."),
        bool("validateOutbound", "Validate Outbound", false, "If checked, the outbound message is validated too, after the transformer. An invalid outbound message is always rejected."),
        opt("invalidMessages", "Invalid Messages", ["Reject", "Accept"], "Reject", "Reject: an invalid inbound message gets status ERROR, with the validation issues as the error. Accept: it is processed normally, and the connector map gets fhirValid (true/false), fhirIssueCount and fhirIssues (the issues as text)."),
        opt("failOn", "Invalid When", ["Errors", "Warnings"], "Errors", "Errors: a message is invalid when validation finds errors. Warnings: warnings make it invalid too."),
        text("profiles", "Profile Packages", "", "Files or folders on the server with profiles, separated by ';'. FHIR packages (.tgz, e.g. nictiz.fhir.nl.r4.zib2020 with its dependencies) and StructureDefinition, ValueSet and CodeSystem files (.json or .xml). A resource is validated against the profiles in its meta.profile."),
        text("requiredProfile", "Required Profile", "", "Canonical URL of a profile every message must conform to, whatever its meta.profile says. Leave empty to only use meta.profile."),
        opt("unknownCodeSystems", "Unknown Code Systems", ["Warning", "Error", "Information"], "Warning", "How a code from a code system the validator does not know is reported (for example SNOMED CT or LOINC)."),
        bool("anyExtensionsAllowed", "Allow Unknown Extensions", true, "If checked, extensions whose definition the validator does not know are accepted. If not checked, they are errors."),
        text("terminologyServer", "Terminology Server", "", "Optional base URL of a FHIR terminology server to check codes the validator cannot check itself. Leave empty to validate offline.")
      ]
    },
    {
      key: "batchProperties",
      label: "Batch",
      class: `${PKG}.FhirBatchProperties`,
      fields: [
        {
          key: "splitType",
          label: "Split Batch By",
          type: "select",
          options: [
            { value: "Bundle_Entry", label: "Bundle Entry" },
            { value: "JavaScript", label: "JavaScript" }
          ],
          default: "Bundle_Entry",
          hint: "Bundle Entry: every Bundle.entry.resource becomes a separate message, in the format it came in. The source map gets fhirBundleType, fhirBundleId, fhirEntryIndex (0 for the first entry), fhirEntryCount, fhirEntryFullUrl, fhirEntryRequestMethod and fhirEntryRequestUrl. Entries without a resource are skipped; a message that is not a Bundle stays one message. Only used when Process Batch is enabled in the connector."
        },
        code("batchScript", "JavaScript", null, BATCH_SCRIPT_HINT)
      ]
    }
  ]
};

DEF.defaults = (version) => {
  const props = { "@class": DEF.propertiesClass, "@version": version };
  for (const group of DEF.groups) {
    const obj = { "@class": group.class, "@version": version };
    for (const f of group.fields) obj[f.key] = f.default ?? null;
    props[group.key] = obj;
  }
  return props;
};

export function register(platform) {
  platform.registerDataType(DEF.name, DEF);
}
