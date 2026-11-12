/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.NpmPackage;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.util.BundleUtil;

/**
 * The FHIR specification for the validator, in place of HAPI's DefaultProfileValidationSupport,
 * which parses every resource of it up front (R5: about 520 MB of heap). The validator asks for all
 * StructureDefinitions at once, so those are parsed here too; ValueSets and CodeSystems it asks for
 * one URL at a time, so they are kept as gzipped JSON (a few MB) and parsed the first time they are
 * needed. Same sources as HAPI: the packages of hapi-fhir-validation-resources-r5, and the bundles
 * of the -r4 and -r4b jars. See docs/lazy-loading-measurement.md.
 */
final class LazySpecificationSupport implements IValidationSupport {

    private static final String[] R5_PACKAGES = { "hl7.fhir.r5.core-5.0.0.tgz", "hl7.fhir.uv.extensions.r5-1.0.0.tgz", "hl7.terminology-5.1.0.tgz" };

    /** The files and order of HAPI's DefaultProfileValidationSupportBundleStrategy; R4B uses the v2 and v3 tables of R4. */
    private static final String[] R4_BUNDLES = { "r4/model/valueset/valuesets.xml", "r4/model/valueset/v2-tables.xml", "r4/model/valueset/v3-codesystems.xml",
            "r4/model/profile/profiles-resources.xml", "r4/model/profile/profiles-types.xml", "r4/model/profile/profiles-others.xml",
            "r4/model/extension/extension-definitions.xml" };
    private static final String[] R4B_BUNDLES = { "r4b/model/valueset/valuesets.xml", "r4/model/valueset/v2-tables.xml", "r4/model/valueset/v3-codesystems.xml",
            "r4b/model/profile/profiles-resources.xml", "r4b/model/profile/profiles-types.xml", "r4b/model/profile/profiles-others.xml",
            "r4b/model/extension/extension-definitions.xml" };

    private final FhirContext ctx;
    private final PrePopulatedValidationSupport structureDefinitions;
    /** Canonical URL (without version) to resource type and gzipped JSON. */
    private final Map<String, String> types = new HashMap<>();
    private final Map<String, byte[]> gzipped = new HashMap<>();
    private final Map<String, IBaseResource> parsed = new ConcurrentHashMap<>();

    private LazySpecificationSupport(FhirContext ctx) {
        this.ctx = ctx;
        this.structureDefinitions = new PrePopulatedValidationSupport(ctx);
    }

    static LazySpecificationSupport load(FhirContext ctx) {
        LazySpecificationSupport support = new LazySpecificationSupport(ctx);
        FhirVersionEnum version = ctx.getVersion().getVersion();
        try {
            if (version == FhirVersionEnum.R5) {
                for (String name : R5_PACKAGES) {
                    support.loadPackage("org/hl7/fhir/r5/packages/" + name);
                }
            } else if (version == FhirVersionEnum.R4 || version == FhirVersionEnum.R4B) {
                for (String name : version == FhirVersionEnum.R4B ? R4B_BUNDLES : R4_BUNDLES) {
                    support.loadBundle("org/hl7/fhir/" + name);
                }
            } else {
                // A new version needs the file list of HAPI's own strategy for it (see docs/development.md).
                throw new IllegalArgumentException("No FHIR specification files known for " + version + ".");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load the FHIR " + version + " specification: " + e.getMessage(), e);
        }
        if (support.structureDefinitions.fetchAllStructureDefinitions().isEmpty()) {
            throw new IllegalStateException("The FHIR " + version + " specification is missing from lib/ (hapi-fhir-validation-resources-" + version.name().toLowerCase() + ").");
        }
        return support;
    }

    private void loadPackage(String path) throws IOException {
        NpmPackage npm;
        try (InputStream in = resource(path)) {
            if (in == null) {
                return;
            }
            npm = NpmPackage.fromPackage(in);
        }
        IParser parser = ctx.newJsonParser();
        NpmPackage.NpmPackageFolder folder = npm.getFolders().get("package");
        for (NpmPackage.PackageResourceInformation info : npm.listIndexedResources("StructureDefinition", "ValueSet", "CodeSystem")) {
            // Index file names may carry a folder prefix such as "@package\".
            byte[] json = folder.fetchFile(info.getFilename().replaceAll(".*[\\\\/]", ""));
            if (json == null) {
                continue;
            }
            if ("StructureDefinition".equals(info.getResourceType())) {
                structureDefinitions.addResource(parser.parseResource(new String(json, StandardCharsets.UTF_8)));
            } else {
                keep(info.getUrl(), info.getResourceType(), json);
            }
        }
    }

    private void loadBundle(String path) throws IOException {
        IBaseBundle bundle;
        try (InputStream in = resource(path)) {
            if (in == null) {
                return;
            }
            IParser xml = ctx.newXmlParser().setParserErrorHandler(new LenientErrorHandler(false));
            bundle = (IBaseBundle) xml.parseResource(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        IParser json = ctx.newJsonParser();
        for (IBaseResource r : BundleUtil.toListOfResources(ctx, bundle)) {
            String type = r.fhirType();
            if ("StructureDefinition".equals(type)) {
                structureDefinitions.addResource(r);
            } else if ("ValueSet".equals(type) || "CodeSystem".equals(type)) {
                keep(ctx.newTerser().getSinglePrimitiveValueOrNull(r, "url"), type, json.encodeResourceToString(r).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private InputStream resource(String path) {
        return LazySpecificationSupport.class.getClassLoader().getResourceAsStream(path);
    }

    /** A later resource with the same URL replaces an earlier one, as in HAPI's own loaders. */
    private void keep(String url, String type, byte[] json) throws IOException {
        if (url == null) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(json.length / 8);
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json);
        }
        types.put(url, type);
        gzipped.put(url, bytes.toByteArray());
    }

    private static String withoutVersion(String url) {
        int bar = url == null ? -1 : url.indexOf('|');
        return bar < 0 ? url : url.substring(0, bar);
    }

    private IBaseResource terminology(String url, String type) {
        String key = withoutVersion(url);
        if (key == null || !type.equals(types.get(key))) {
            return null;
        }
        return parsed.computeIfAbsent(key, k -> {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped.get(k)))) {
                return ctx.newJsonParser().parseResource(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** For tests: how many ValueSets and CodeSystems are known, and how many were parsed. */
    int terminologyCount() {
        return types.size();
    }

    int parsedCount() {
        return parsed.size();
    }

    @Override
    public FhirContext getFhirContext() {
        return ctx;
    }

    @Override
    public String getName() {
        return "FHIR " + ctx.getVersion().getVersion() + " specification (lazy terminology)";
    }

    @Override
    public IBaseResource fetchValueSet(String url) {
        return terminology(url, "ValueSet");
    }

    @Override
    public IBaseResource fetchCodeSystem(String url) {
        return terminology(url, "CodeSystem");
    }

    // isValueSetSupported and isCodeSystemSupported are not overridden, as in DefaultProfileValidationSupport:
    // the chain then leaves code validation to the terminology supports, which fetch from here.

    @Override
    public IBaseResource fetchStructureDefinition(String url) {
        return structureDefinitions.fetchStructureDefinition(url);
    }

    @Override
    public <T extends IBaseResource> List<T> fetchAllStructureDefinitions() {
        return structureDefinitions.fetchAllStructureDefinitions();
    }

    @Override
    public <T extends IBaseResource> List<T> fetchAllNonBaseStructureDefinitions() {
        return structureDefinitions.fetchAllNonBaseStructureDefinitions();
    }

    /** Only the StructureDefinitions: listing all terminology would parse all of it, which is what this class avoids. */
    @Override
    public List<IBaseResource> fetchAllConformanceResources() {
        return structureDefinitions.fetchAllConformanceResources();
    }
}
