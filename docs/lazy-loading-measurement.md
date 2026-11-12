# Lazy loading of the FHIR specification: prototype measurement

Measured on 2026-10-26 with FHIR Data Type 1.5.0 (HAPI FHIR 8.12.1, `org.hl7.fhir.core` 6.9.12), Java 17, in a JUnit test outside the plugin. The question was whether the validator could load only what a message needs, instead of the whole FHIR specification. The measurement code is not part of the repository.

## What the validator asks for

A recording wrapper around HAPI's `DefaultProfileValidationSupport` logged every call while a Patient, an Observation and a Bundle were validated (R4 and R5 alike):

| Kind | How the validator asks | Lazy loading possible? |
| --- | --- | --- |
| StructureDefinitions | `fetchAllStructureDefinitions()`, once, at the first validation | No: it wants all of them |
| ValueSets, CodeSystems | `fetchValueSet(url)` / `fetchCodeSystem(url)`, one at a time, only for the bindings in the message (6 for a Patient, 8 for an Observation) | Yes |
| Extension definitions | `fetchStructureDefinition(url)`, when an extension is used | Yes |

Classes of the FHIR model are already loaded lazily by Java and HAPI, so restricting the resource types would hardly save anything.

## Where R5's memory goes

HAPI loads three packages for R5 (`DefaultProfileValidationSupportNpmStrategy`); for R4 it loads the specification bundles only.

| R5 package | Resources | Heap |
| --- | --- | --- |
| `hl7.fhir.r5.core` 5.0.0 | 1,543 | 266 MB |
| `hl7.fhir.uv.extensions.r5` 1.0.0 | 609 | 59 MB |
| `hl7.terminology` 5.1.0 | 3,568 | 159 MB |

By type, parsed: 828 StructureDefinitions 271 MB, 1,620 CodeSystems 148 MB, 3,272 ValueSets 53 MB. Leaving out the terminology package is not an option: the Observation test message then gets warnings for codes the validator can no longer check.

## Prototype

A validation support that keeps the StructureDefinitions loaded as now, and keeps every ValueSet and CodeSystem as gzipped JSON per URL, parsed the first time the validator asks for it (and kept after that). Variant: the StructureDefinitions of the extension package lazy as well.

| | Default | Lazy terminology | + lazy extensions | Same validation result |
| --- | --- | --- | --- | --- |
| R5 | 524 MB | 266 MB (−49%) | 253 MB (−52%) | 7/7 and 5/5 messages |
| R4 | 189 MB | 162 MB (−14%) | not measured | 6/6 messages |

- The test messages include invalid ones (unknown gender, status and category codes, an extension with the wrong value type); every issue was identical to the default, including the error texts.
- All 4,890 R5 ValueSets and CodeSystems take 6 MB gzipped; the seven messages needed 14 of them parsed.
- Loading the lazy support took 3.5 s, against about 10 s for the default up to the first validation result.
- What stays is mostly the 316 core StructureDefinitions of R5 (about 240 MB), which the validator asks for all at once.

## Conclusion

Lazy loading of terminology is worth it for R5: about half the memory, with the same validation results. For R4 the gain is small. Restricting the resource types is not needed.

To make it a feature: replace `DefaultProfileValidationSupport` in `HapiFhirEngine.build` by a lazy support for the specification (R5 from the three packages in the `hapi-fhir-validation` jar; R4 from the bundles of `hapi-fhir-validation-resources-r4`), so the static copy in `DefaultProfileValidationSupport.ourImplementations` is no longer filled. Before releasing: test with Nictiz packages, a terminology server, Bundles with many resource types, and `Unknown Code Systems` set to Error, and compare the results with 1.5.0 on the build.fhir.org examples.

## Implemented in 1.5.1

`LazySpecificationSupport` (issue #26) replaces `DefaultProfileValidationSupport` in `HapiFhirEngine`. It keeps all StructureDefinitions loaded, and ValueSets and CodeSystems gzipped until they are needed. It reads the same files in the same order as HAPI; for R4B that includes the v2 and v3 tables of R4, which the first prototype missed.

`LazySpecificationTest` compares the issues of 40 messages with HAPI's own support for R4, R4B and R5, and all are identical. The 40 messages are 10 crafted ones, the 3 plugin examples and the 27 build.fhir.org Patient examples, many of them invalid. Two lessons from that test:

- **Overriding `isValueSetSupported` / `isCodeSystemSupported` changes the result**, because the chain then routes code validation to this support. HAPI's support does not override them, so this one does not either.
- **A later resource with the same URL replaces an earlier one**, as in HAPI.

Measured on the packaged zip, through the plugin's isolated `lib/` class loader, against the released 1.5.0 zip:

| | 1.5.0 | 1.5.1 |
| --- | --- | --- |
| R5 | 523 MB, first validations 8.8 s | 300 MB, 5.7 s |
| R4 + R5 | 675 MB | 428 MB |
| After releasing both | 38 MB | 36 MB |
