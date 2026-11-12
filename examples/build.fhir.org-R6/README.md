# Patient examples from build.fhir.org (FHIR R6)

The 27 Patient examples from [build.fhir.org/patient-examples.html](https://build.fhir.org/patient-examples.html), in JSON (`json/`) and XML (`xml/`), downloaded on 2026-09-26.

**These are FHIR R6 examples** (`6.0.0-snapshot1`, the current development build), while this data type validates **R4**. Validated with the data type:

- **25 of 27 are valid against R4**, in both JSON and XML: good test messages for a channel.
- **2 are rejected** because they use elements that are new in R6. They are useful as examples of invalid messages:

| Example | R4 error |
| --- | --- |
| `patient-example` | `Patient.contact.additionalName` does not exist in R4 |
| `patient-example-newborn` | `Patient.contact.role` does not exist in R4 |

Both elements are new in R6, not in R5: with *FHIR Version* R5 these two are rejected as well. For an element that does exist in R5 but not in R4, see `Observation.triggeredBy` in the tests (`FhirVersionTest`).

`patient-examples-general` and `patient-examples-cypress-template` are Bundles with several patients: one message each.

For examples that are guaranteed to be R4, see [hl7.org/fhir/R4/patient-examples.html](https://hl7.org/fhir/R4/patient-examples.html).

The FHIR specification, including its examples, is published by HL7 under [CC0](https://hl7.org/fhir/R4/license.html).
