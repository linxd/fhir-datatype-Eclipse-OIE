# FHIR Data Type for Open Integration Engine

[![Build](https://github.com/matthydehoog/fhir-datatype-Eclipse-OIE/actions/workflows/build.yml/badge.svg)](https://github.com/matthydehoog/fhir-datatype-Eclipse-OIE/actions/workflows/build.yml)

An HL7 FHIR data type for [Eclipse Open Integration Engine](https://github.com/OpenIntegrationEngine) (OIE, a fork of Mirth Connect):

- **FHIR JSON and FHIR XML in**: the format is detected per message.
- **FHIR XML in the transformer** (E4X), optionally without the FHIR namespace: `msg.name.family.@value`.
- **FHIR JSON or FHIR XML out**, chosen per connector.
- **Validation** with [HAPI FHIR](https://hapifhir.io) against the FHIR R4, R4B or R5 specification and your own profiles and packages (for example Nictiz zib2020 / nl-core). It checks structure, data types, cardinality, required elements, value set bindings and invariants.
- **Invalid messages** are rejected (status ERROR, with the issues as the error) or accepted with the issues in the connector map; you choose per connector.
- **Batch processing**: a Bundle can be split into one message per entry, each validated on its own.

FHIR R4 (4.0.1), R4B (4.3.0) and R5 (5.0.0) are supported, chosen per connector with *FHIR Version*. Channels saved with an earlier version of the plugin keep R4.

## Online course

[FHIR Data Type for Eclipse OIE](https://eclipse-oie.moodiy.cloud/course/view.php?id=9) on the Academy for Eclipse OIE is a free course on this plugin: installation and properties, validation, FHIR in the transformer and scripts, and 17 hands-on exercises with a test channel and fictitious messages, ending with a final test. Create an account on the site and click *Enrol me* on the course page.

## Installation

1. Download `datatype-fhir-<version>.zip` from the [releases](https://github.com/matthydehoog/fhir-datatype-Eclipse-OIE/releases).
2. In the Administrator: *Settings > Extensions > Install Extension*, or unzip it into `<OIE_HOME>/extensions/`.
3. Restart the OIE service.

**Memory.** The validator loads the FHIR specification once per FHIR version in use: about 130 MB of heap for R4 and 300 MB for R5, more with large profile packages. Since 1.5.1 the value sets and code systems are only read when a message needs them (before: R4 about 190 MB, R5 about 520 MB). Channels that use the same version share it. When no deployed channel uses a version any more (undeployed, or switched to another version), it is released right after the deploy or undeploy; validators used only from scripts (`FhirTools`) or by other plugins are released 15 minutes after their last use. Give the server at least 1 GB, and 2 GB if channels use R5 next to another version: put `-Xmx1g` (or more) on its own line in `<OIE_HOME>/conf/custom.vmoptions` and restart the service.

Java only reuses released memory after a garbage collection, and an idle server hardly collects, so Task Manager keeps showing the maximum. To let the server collect when it is idle and give memory back to Windows, also add `-XX:G1PeriodicGCInterval=60000` (one collection per idle minute) on its own line in `custom.vmoptions`. Don't edit `oieserver.vmoptions` or `oieservice.vmoptions`: both include `conf/custom.vmoptions`, so the setting applies to the service and to `oieserver` alike. The first validation after a deploy takes a few seconds; the plugin starts loading when the channel is deployed, and after that a message takes tens of milliseconds.

OIE's default heap is only 256 MB (`-Xmx256m` in `conf/base_includes.vmoptions`, in 4.6.0 and 4.7.0 alike; an installer may have set more). That is not enough for validation: the first message that needs the validator fails with `java.lang.OutOfMemoryError: Java heap space` (for example while loading the UCUM units), and the message stays at RECEIVED. So set `-Xmx1g` in `conf/custom.vmoptions` before you deploy a FHIR channel, also on a new or upgraded 4.7.0 server whose `custom.vmoptions` has no `-Xmx` line yet.

**Size.** HAPI FHIR and its libraries make the zip about 117 MB (the specifications of R4, R4B and R5 included). They sit in `extensions/datatype-fhir/lib/` and are loaded in the plugin's own class loader, so they never clash with the libraries OIE ships.

## Properties

| Property | Default | What it does |
| --- | --- | --- |
| FHIR Version | R4 | The FHIR version of the messages: R4, R4B or R5. Used for validation and for converting between JSON and XML; profile packages must be for the same version. |
| Resource Types | empty | The resource types the connector accepts, separated by commas, e.g. `Patient, Observation, Bundle`. A message of another type, or a Bundle with an entry of another type, is invalid; see [Resource Types](#resource-types). Empty accepts every type. |
| Transformer Format | XML | What the transformer works on: FHIR XML with E4X, or a JavaScript object of the FHIR JSON (see [JSON in the transformer](#json-in-the-transformer)). |
| Strip Namespaces | on | With Transformer Format XML: removes the FHIR namespace from the transformer XML (the XHTML namespace of `text.div` stays) and puts it back afterwards. |
| Output Format | JSON | FHIR JSON or FHIR XML for the outbound message. Both are written by the FHIR engine in the element order of the specification. |
| Pretty Print | on | Indents JSON and XML created by the FHIR engine. |
| Validate Inbound | on | Validates every inbound message. |
| Validate Outbound | off | Validates the outbound message after the transformer; an invalid one is always rejected. |
| Invalid Messages | Reject | *Reject*: status ERROR with the issues. *Accept*: processed normally, issues in the connector map. |
| Invalid When | Errors | *Errors*, or *Warnings* to treat warnings as invalid too. |
| Profile Packages | empty | Files or folders on the server with profiles, separated by `;`. |
| Required Profile | empty | Canonical URL every message must conform to, in addition to its `meta.profile`. |
| Unknown Code Systems | Warning | How codes from code systems the validator does not know (SNOMED CT, LOINC, ...) are reported. |
| Allow Unknown Extensions | on | Accept extensions whose definition is unknown. |
| Terminology Server | empty | Optional FHIR terminology server for codes the validator cannot check offline. |
| Split Batch By (Batch) | Bundle Entry | *Bundle Entry*: every `Bundle.entry.resource` is one message. *JavaScript*: your own splitter. Only used when *Process Batch* is on in the source connector; see [Batch processing](#batch-processing). |

**Every connector validates with its own properties.** A destination with data type FHIR validates the message again, with the settings of its own inbound properties, not those of the source. With the defaults it validates without your *Profile Packages*, so a message with `meta.profile` set fails there with *"Profile reference … has not been checked because it could not be found"*. Turn off *Validate Inbound* on the destinations: the source has already validated the message, and the message is validated only once. If a destination must validate, give it the same *Profile Packages* (and *Required Profile*) as the source.

### Connector map

Inbound validation sets these variables, also when invalid messages are accepted:

| Variable | Value |
| --- | --- |
| `fhirValid` | `true` or `false` |
| `fhirIssueCount` | number of errors and warnings |
| `fhirIssues` | one line per issue: `error Patient.gender (line 1, column 39): ...` |
| `fhirOperationOutcome` | the issues as a FHIR `OperationOutcome` resource (JSON), ready to return to the sender |
| `mirth_type` | resource type, e.g. `Patient` or `Bundle` |
| `mirth_version` | the *FHIR Version*: `R4`, `R4B` or `R5` |

For example, a source filter that only lets valid messages through, with *Invalid Messages* on *Accept*:

```javascript
if ($('fhirValid') == false) {
    logger.warn('Invalid FHIR message:\n' + $('fhirIssues'));
    return false;
}
return true;
```

### Resource Types

*Resource Types* limits the resource types a connector accepts, for example `Patient, Observation, Bundle`. A message of another type is invalid with one error, for example:

```
error Encounter: Resource type Encounter is not in Resource Types (Patient, Observation, Bundle)
```

- For a Bundle, every `Bundle.entry.resource` is checked too (also those of a Bundle inside an entry), so the list needs `Bundle` and the types of its entries. The error names the entry: `Bundle.entry[1].resource`.
- *Invalid Messages* decides what happens, as for validation errors: *Reject* gives status ERROR (and HTTP `400` with an auto-generated response), *Accept* processes the message with `fhirValid` = `false` and the error in `fhirIssues` and `fhirOperationOutcome`.
- A refused message is not validated further. An accepted type is validated as without the property.
- The check also works with *Validate Inbound* off. The connector map then only gets the validation variables for a refused message.
- Names are case-sensitive and may be separated by commas, semicolons or spaces. Contained resources are not checked. With *Process Batch* each entry is its own message and is checked on its own.
- It is a check on the input, not a memory saving: the validator loads the whole specification of the *FHIR Version* whatever the list is (see the [development guide](docs/development.md#validation)).

## Batch processing

Turn on **Process Batch** in the source connector's settings to split a Bundle into one message per entry, for example a `batch` or `collection` Bundle of Observations. With *Split Batch By: Bundle Entry* (the default) every `Bundle.entry.resource` becomes a separate message, in the format it came in (JSON or XML), and is validated and transformed on its own.

Each message gets these source map variables:

| Variable | Value |
| --- | --- |
| `fhirBundleType` | `Bundle.type`, e.g. `batch`, `transaction` or `collection` |
| `fhirBundleId` | `Bundle.id`, when the Bundle has one |
| `fhirEntryIndex` | position of the entry in the Bundle: 0 for the first, as in `Bundle.entry[0]` |
| `fhirEntryCount` | number of entries in the Bundle |
| `fhirEntryFullUrl` | the entry's `fullUrl`, when it has one |
| `fhirEntryRequestMethod` | the entry's `request.method` (`POST`, `PUT`, ...), when it has one |
| `fhirEntryRequestUrl` | the entry's `request.url`, when it has one |

For example `$('fhirEntryRequestMethod')` in a transformer. The engine also adds its own `batchSequenceId` (1 for the first message).

- Entries without a resource (a `DELETE` in a transaction) give no message; `fhirEntryIndex` still counts them.
- A message that is not a Bundle stays one message. So does a message that cannot be read: validation then reports what is wrong with it.
- References between entries (`urn:uuid:` in a transaction) are not resolved: each resource is validated as it is.
- *Split Batch By: JavaScript* works as in the other data types: a script that reads from `reader` and returns the next message, or nothing at the end.

## Responding to the sender

A FHIR client expects an `OperationOutcome` back, and HTTP `400` when it sent something invalid. With the source connector's **Response** set to *Auto-generate (After source transformer)* (or *Before processing* / *Destinations completed*), the data type answers with an `OperationOutcome`, in JSON or XML like the message that came in:

| Situation | OperationOutcome | HTTP status |
| --- | --- | --- |
| Rejected as invalid | every error and warning, with its location and line/column | 400 |
| Accepted | the warnings (and errors, with *Invalid Messages: Accept*), or "The message was accepted." | 200 |
| Filtered | "The message was filtered and not processed further." | 200 |
| Any other error (e.g. in a transformer) | the error, with code `exception` | 500 |

The HTTP status and content type are put in the channel map and the source map (so they also show in the message's source map). Use them in the **HTTP Listener** settings:

| HTTP Listener setting | Value |
| --- | --- |
| Response Status Code | `${fhirHttpStatus}` |
| Response Content Type | `${fhirContentType}` (`application/fhir+json` or `application/fhir+xml`) |

Without these, the listener answers `500` for a rejected message instead of `400`, still with the OperationOutcome as the body.

To build your own response, use `$('fhirOperationOutcome')`, which is filled whenever inbound validation runs.

## Profiles and packages

Put the packages or files on the server and enter the folder in *Profile Packages*, for example `C:\fhir\profiles` or `/opt/oie/fhir/profiles`. A folder is read with its subfolders. Accepted:

- **FHIR packages** (`.tgz`) as downloaded from [packages.fhir.org](https://packages.fhir.org) or [simplifier.net](https://simplifier.net). Include every package a package depends on (the core package of the chosen version, such as `hl7.fhir.r4.core`, is built in). For the Dutch profiles: `nictiz.fhir.nl.r4.nl-core` and `nictiz.fhir.nl.r4.zib2020`.
- **Conformance resources**: StructureDefinition, ValueSet, CodeSystem, ConceptMap and NamingSystem files in JSON or XML.

Packages and profiles must be for the channel's *FHIR Version*: the `fhirVersions` of a package and the `fhirVersion` of a StructureDefinition are checked when the validator is built. An R4 package such as the Nictiz zib2020 on an R5 channel is refused with an error like *"Package nictiz.fhir.nl.r4.zib2020#0.12.1-beta.1 is for FHIR 4.0.1, but FHIR Version is R5 (5.0.0)"*, on every message until you fix the setting.

A resource is validated against the profiles in its `meta.profile`. Set *Required Profile* to validate every message against one profile, for example `http://nictiz.nl/fhir/StructureDefinition/nl-core-Patient`. An unknown profile is an error.

Profiles are loaded once per combination of settings and kept in memory; restart the channel after changing the files.

## The transformer

With *Strip Namespaces* on, the transformer works on plain FHIR XML:

```javascript
var family = msg.name[0].family.@value.toString();
msg.gender.@value = 'female';

// A new element: FHIR XML keeps values in the value attribute.
msg.appendChild(<birthDate value="1980-01-01"/>);
```

You don't have to mind the FHIR element order: after the transformer the FHIR engine writes the message again, JSON or XML, in the order of the specification. So `birthDate` ends up after `gender`, wherever you appended it. An element that is not in the specification is refused with an error instead of being dropped.

### JSON in the transformer

Set *Transformer Format* to **JSON** on the inbound properties, and `msg` is a JavaScript object of the FHIR JSON, as in most FHIR tooling:

```javascript
var family = msg.name[0].family;
msg.gender = 'female';
msg.identifier = (msg.identifier || []).concat([{ system: 'http://example.org/mrn', value: '12345' }]);
```

- Messages can still come in as JSON or XML; XML is converted to JSON for the transformer. JSON is passed on exactly as it came in.
- The outbound message is made from `msg` and written by the FHIR engine in the order of the specification, JSON or XML as set in *Output Format*. A misspelled element (`msg.gendr`) is refused with an error.
- **Decimals lose trailing zeros**: JavaScript turns `1.50` into the number `1.5`, so the outbound message says `1.5`. In FHIR the number of decimals can carry meaning (precision). Keep *Transformer Format* on XML for messages where that matters.
- On the outbound properties, *Transformer Format* sets the format of the outbound template (`tmp`).
- In the Administrator, use a FHIR JSON message as the inbound template: converting an XML template to JSON needs the FHIR engine, which runs on the server only.

### In scripts

`FhirTools` validates and converts in any script, for example an HTTP response:

```javascript
var FhirTools = Packages.com.mirth.connect.plugins.datatypes.fhir.FhirTools;

var result = FhirTools.validate(response.getMessage());
if (!result.isValid()) {
    logger.warn(result.summary());
}

var json = FhirTools.toJson(msg.toString()); // transformer XML to JSON
```

`FhirTools.validate(resource, requiredProfile, profilePackages)` validates against your own profiles. The functions use R4; for R4B or R5 add the version as last argument: `FhirTools.validate(resource, requiredProfile, profilePackages, 'R5')`, `FhirTools.toJson(xml, 'R5')`, `FhirTools.toXml(json, 'R5')`. `validate` and `toJson` accept FHIR XML with or without the FHIR namespace, so `msg.toString()` works in a transformer with *Strip Namespaces* on.

### Reference list

The script editors' reference list has a **FHIR Functions** category with 16 drag-and-drop snippets: `FhirTools` (validate, also against a profile; walk through the issues; convert XML to JSON and back), the inbound validation variables (`fhirValid`, `fhirIssueCount`, `fhirIssues`, `fhirOperationOutcome`) with a filter rule for invalid messages, and E4X patterns for FHIR XML. In the web administrator, import [`examples/fhir-functions-code-templates.xml`](examples/fhir-functions-code-templates.xml) to get them.

See [docs/code-templates.md](docs/code-templates.md) for how to use them, what each template does and what to adapt.

## Behaviour to know

- **No transformer**: without filter or transformer steps a message passes through unchanged, unless the output format differs from the input (JSON in, XML out or the other way round); then it is converted.
- **Unknown elements**: the outbound message (after a transformer, or when it is converted) refuses elements that are not in the specification instead of dropping them silently. Inbound validation reports them first.
- **XML output after a transformer** is written again by the FHIR engine, so its element order is always that of the specification. XML comments and the formatting of the transformer are not kept; *Pretty Print* decides the layout.
- **Bundles** are one message, unless *Process Batch* is on in the source connector (see [Batch processing](#batch-processing)).
- **Accept on outbound** does not exist: an invalid outbound message is always rejected, because there is no later step to handle it.
- **A DOCTYPE is refused**: FHIR XML with a `<!DOCTYPE …>` ends in ERROR (see [Security: XML External Entities](#security-xml-external-entities-xxe)).

## Security: XML External Entities (XXE)

An XXE attack hides a DTD with entity declarations in an XML message, so that the parser reads a local file (for example a configuration with passwords) or calls an internal address. Several such vulnerabilities were found in HAPI FHIR and `org.hl7.fhir.core`: [CVE-2024-45294](https://advisories.gitlab.com/pkg/maven/ca.uhn.hapi.fhir/org.hl7.fhir.utilities/CVE-2024-45294/), [CVE-2024-51132](https://github.com/advisories/GHSA-4cf2-cxp3-rjr7), [CVE-2024-52007](https://github.com/advisories/GHSA-gr3c-q7xf-47vh) and [CVE-2026-55471](https://github.com/advisories/GHSA-2f55-g35j-5jmf).

This data type is not affected by them:

- It ships **HAPI FHIR 8.12.1 with `org.hl7.fhir.core` 6.9.12**, which contains the fixes for all four (the last one was fixed in 6.9.10).
- Its own XML handling does not resolve entities: the Bundle splitter does not allow a DOCTYPE, and the HAPI parser refuses undeclared entities.
- A probe with an external entity, a parameter entity and a "billion laughs" through every XML path (inbound validation, to the transformer, JSON and XML output, `FhirTools`, the Bundle splitter) leaked nothing.

As an extra line of defence, since **1.4.1** the data type refuses FHIR XML with a `<!DOCTYPE …>` before anything parses it, inbound, without a transformer, outbound and in `FhirTools`, with the error *"FHIR XML must not contain a DOCTYPE (DTD) declaration"*. The FHIR specification does not allow DTDs, so valid FHIR is never affected. This also keeps a DOCTYPE away from the engine's own XML handling in the transformer.

Keep the data type up to date: a new HAPI FHIR version is included in a new release of this plugin.

## Examples

[`examples/`](examples) has test messages: a valid and an invalid Patient and a transaction Bundle, plus the 27 Patient examples from build.fhir.org in JSON and XML ([`examples/build.fhir.org-R6`](examples/build.fhir.org-R6)). Those are FHIR R6 examples: 25 are valid against R4, and 2 are rejected for elements that are new in R6.

## Building

JDK 17 and Maven. The engine jars (`com.mirth.connect:*:4.6.0`) must be in the local Maven repository: `bash .github/scripts/install-oie-jars.sh 4.6.0` puts them there. Every push and pull request is built and tested on GitHub against OIE 4.6.0 and 4.7.0, including a smoke test in a running engine.

```bash
mvn package
```

The result is `target/datatype-fhir-<version>.zip`. The plugin jars target Java 11; the FHIR engine needs Java 17 (HAPI FHIR 8), which OIE ships with.

See the [development guide](docs/development.md) for how to install the engine jars, the architecture, testing and releasing.

## License

[Mozilla Public License 2.0](LICENSE). HAPI FHIR is licensed under the Apache License 2.0.
