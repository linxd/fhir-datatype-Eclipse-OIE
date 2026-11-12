/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.RemoteTerminologyServiceValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.UnknownCodeSystemWarningValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.hl7.fhir.utilities.npm.NpmPackage;

import com.mirth.connect.plugins.datatypes.fhir.FhirEngine;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * HAPI FHIR behind {@link FhirEngine}. A validator is expensive to build (the specification's
 * StructureDefinitions and the profiles), so one is kept per set of options; FhirValidator is thread
 * safe. The specification itself is shared per version ({@link LazySpecificationSupport}).
 */
public final class HapiFhirEngine implements FhirEngine {

    /** Conformance resources taken from profile files and packages; examples and the like are skipped. */
    private static final Set<String> CONFORMANCE_TYPES = Set.of("StructureDefinition", "ValueSet", "CodeSystem", "ConceptMap", "NamingSystem");

    private final Map<String, CachedValidator> validators = new ConcurrentHashMap<>();
    private final Map<FhirVersionEnum, FhirContext> validationContexts = new HashMap<>();
    private final Map<FhirVersionEnum, LazySpecificationSupport> specifications = new HashMap<>();

    @Override
    public List<Map<String, String>> validate(String resource, Map<String, String> options) throws Exception {
        return withEngineClassLoader(() -> {
            FhirValidator validator = validator(options);
            ValidationOptions validationOptions = new ValidationOptions();
            String required = options.getOrDefault(REQUIRED_PROFILE, "").trim();
            if (!required.isEmpty()) {
                validationOptions.addProfile(required);
            }
            ValidationResult result = validator.validateWithResult(resource, validationOptions);
            List<Map<String, String>> issues = new ArrayList<>();
            for (SingleValidationMessage m : result.getMessages()) {
                Map<String, String> issue = new LinkedHashMap<>();
                issue.put(SEVERITY, m.getSeverity() == null ? "error" : m.getSeverity().getCode());
                issue.put(LOCATION, m.getLocationString() == null ? "" : m.getLocationString());
                issue.put(LINE, m.getLocationLine() == null ? "" : String.valueOf(m.getLocationLine()));
                issue.put(COLUMN, m.getLocationCol() == null ? "" : String.valueOf(m.getLocationCol()));
                issue.put(MESSAGE, m.getMessage() == null ? "" : m.getMessage());
                issue.put(MESSAGE_ID, m.getMessageId() == null ? "" : m.getMessageId());
                issues.add(issue);
            }
            return issues;
        });
    }

    @Override
    public String toJson(String xml, String fhirVersion, boolean pretty) throws Exception {
        return withEngineClassLoader(() -> {
            FhirContext ctx = context(fhirVersion);
            IBaseResource resource = strict(ctx.newXmlParser()).parseResource(xml);
            return ctx.newJsonParser().setPrettyPrint(pretty).encodeResourceToString(resource);
        });
    }

    @Override
    public String toXml(String json, String fhirVersion, boolean pretty) throws Exception {
        return withEngineClassLoader(() -> {
            FhirContext ctx = context(fhirVersion);
            IBaseResource resource = strict(ctx.newJsonParser()).parseResource(json);
            return ctx.newXmlParser().setPrettyPrint(pretty).encodeResourceToString(resource);
        });
    }

    @Override
    public String reorderXml(String xml, String fhirVersion, boolean pretty) throws Exception {
        return withEngineClassLoader(() -> {
            FhirContext ctx = context(fhirVersion);
            IBaseResource resource = strict(ctx.newXmlParser()).parseResource(xml);
            return ctx.newXmlParser().setPrettyPrint(pretty).encodeResourceToString(resource);
        });
    }

    @Override
    public String reorderJson(String json, String fhirVersion, boolean pretty) throws Exception {
        return withEngineClassLoader(() -> {
            FhirContext ctx = context(fhirVersion);
            IBaseResource resource = strict(ctx.newJsonParser()).parseResource(json);
            return ctx.newJsonParser().setPrettyPrint(pretty).encodeResourceToString(resource);
        });
    }

    @Override
    public void warmUp(Map<String, String> options) {
        try {
            withEngineClassLoader(() -> validator(options));
        } catch (Exception e) {
            // Reported again, with the details, when the first message is validated.
        }
    }

    @Override
    public synchronized void retainValidators(List<Map<String, String>> inUse, long idleMillis) {
        Set<String> keep = new HashSet<>();
        for (Map<String, String> options : inUse) {
            keep.add(key(options));
        }
        long now = System.currentTimeMillis();
        validators.entrySet().removeIf(e -> !keep.contains(e.getKey()) && now - e.getValue().lastUsed > idleMillis);
        Set<FhirVersionEnum> needed = new HashSet<>();
        for (CachedValidator cached : validators.values()) {
            needed.add(cached.version);
        }
        for (FhirVersionEnum version : new HashSet<>(validationContexts.keySet())) {
            if (!needed.contains(version)) {
                validationContexts.remove(version);
                specifications.remove(version);
                // Not filled by the engine any more (LazySpecificationSupport), but cleared in case anything did.
                releaseSpecification(version);
            }
        }
    }

    /**
     * HAPI's DefaultProfileValidationSupport keeps the specification of each version in a static
     * map for the life of its class loader (R5: about 520 MB), with no API to drop it. Since 1.5.1
     * the engine uses LazySpecificationSupport and never fills it; this is a safety net, in case
     * something in HAPI builds one after all. The map is this plugin's own: HAPI is loaded in the
     * plugin's class loader.
     */
    static boolean releaseSpecification(FhirVersionEnum version) {
        try {
            Map<?, ?> implementations = hapiStaticSpecifications();
            synchronized (implementations) {
                return implementations.remove(version) != null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A HAPI FHIR without this field: the specification stays loaded, as before 1.5.0.
            return false;
        }
    }

    /** DefaultProfileValidationSupport.ourImplementations; package-private for tests. */
    static Map<?, ?> hapiStaticSpecifications() throws ReflectiveOperationException {
        Field field = DefaultProfileValidationSupport.class.getDeclaredField("ourImplementations");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    /** The cache keys of the validators, for tests. */
    Set<String> validatorKeys() {
        return new HashSet<>(validators.keySet());
    }

    /** The FHIR versions whose specification is loaded for validation, for tests. */
    synchronized Set<FhirVersionEnum> loadedVersions() {
        return new HashSet<>(validationContexts.keySet());
    }

    // ------------------------------------------------------------------ validator

    private static final class CachedValidator {
        final FhirValidator validator;
        final FhirVersionEnum version;
        volatile long lastUsed = System.currentTimeMillis();

        CachedValidator(FhirValidator validator, FhirVersionEnum version) {
            this.validator = validator;
            this.version = version;
        }
    }

    static String key(Map<String, String> options) {
        Map<String, String> key = new TreeMap<>(options);
        key.remove(REQUIRED_PROFILE); // passed per call, not part of the validator
        key.put(FHIR_VERSION, version(options.get(FHIR_VERSION)).name());
        return key.toString();
    }

    private FhirValidator validator(Map<String, String> options) {
        CachedValidator cached = validators.computeIfAbsent(key(options), k -> build(options));
        cached.lastUsed = System.currentTimeMillis();
        return cached.validator;
    }

    /**
     * The specification of a version is loaded into the context the validator uses, and stays there
     * as long as the context lives. Validators therefore get a context of this engine instead of
     * the static cached one, so it can be released with them.
     */
    private synchronized FhirContext validationContext(FhirVersionEnum version) {
        return validationContexts.computeIfAbsent(version, FhirContext::forVersion);
    }

    /**
     * One specification per version, shared by its validators like HAPI's static copy was. It is
     * also the context's own support, so HAPI never builds a DefaultProfileValidationSupport (and
     * its full, static copy of the specification) behind our back.
     */
    private synchronized LazySpecificationSupport specification(FhirVersionEnum version, FhirContext ctx) {
        return specifications.computeIfAbsent(version, v -> {
            LazySpecificationSupport spec = LazySpecificationSupport.load(ctx);
            ctx.setValidationSupport(spec);
            return spec;
        });
    }

    /** The loaded specification of a version, or null; for tests. */
    synchronized LazySpecificationSupport loadedSpecification(FhirVersionEnum version) {
        return specifications.get(version);
    }

    private CachedValidator build(Map<String, String> options) {
        FhirVersionEnum version = version(options.get(FHIR_VERSION));
        FhirContext ctx = validationContext(version);

        List<IValidationSupport> supports = new ArrayList<>();
        PrePopulatedValidationSupport profiles = loadProfiles(ctx, options.getOrDefault(PROFILES, ""));
        if (profiles != null) {
            supports.add(profiles);
        }
        supports.add(specification(version, ctx));
        supports.add(new CommonCodeSystemsTerminologyService(ctx));
        supports.add(new InMemoryTerminologyServerValidationSupport(ctx));
        supports.add(new SnapshotGeneratingValidationSupport(ctx));
        String terminologyServer = options.getOrDefault(TERMINOLOGY_SERVER, "").trim();
        if (!terminologyServer.isEmpty()) {
            supports.add(new RemoteTerminologyServiceValidationSupport(ctx, terminologyServer));
        }
        UnknownCodeSystemWarningValidationSupport unknown = new UnknownCodeSystemWarningValidationSupport(ctx);
        unknown.setNonExistentCodeSystemSeverity(severity(options.getOrDefault(UNKNOWN_CODE_SYSTEMS, "warning")));
        supports.add(unknown);

        FhirInstanceValidator module = new FhirInstanceValidator(new ValidationSupportChain(supports));
        module.setAnyExtensionsAllowed(!"false".equalsIgnoreCase(options.get(ANY_EXTENSIONS_ALLOWED)));
        module.setErrorForUnknownProfiles(true);
        // Recommendations such as dom-6 ("should have narrative") would flag nearly every message.
        module.setBestPracticeWarningLevel(BestPracticeWarningLevel.Ignore);

        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(module);
        return new CachedValidator(validator, version);
    }

    /**
     * Profiles from files and folders: NPM packages (.tgz, as published on packages.fhir.org, e.g.
     * nictiz.fhir.nl.r4.zib2020) and single conformance resources in JSON or XML.
     */
    private static PrePopulatedValidationSupport loadProfiles(FhirContext ctx, String paths) {
        List<File> files = new ArrayList<>();
        for (String path : paths.split(";")) {
            if (!path.trim().isEmpty()) {
                collect(new File(path.trim()), files);
            }
        }
        if (files.isEmpty()) {
            return null;
        }
        PrePopulatedValidationSupport support = new PrePopulatedValidationSupport(ctx);
        for (File file : files) {
            String name = file.getName().toLowerCase();
            try {
                if (name.endsWith(".tgz") || name.endsWith(".tar.gz")) {
                    loadPackage(ctx, file, support);
                } else {
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    IParser parser = name.endsWith(".xml") ? ctx.newXmlParser() : ctx.newJsonParser();
                    IBaseResource resource = parser.parseResource(text);
                    if ("StructureDefinition".equals(resource.fhirType())) {
                        checkVersion(ctx, ctx.newTerser().getSinglePrimitiveValueOrNull(resource, "fhirVersion"), "StructureDefinition " + file.getName());
                    }
                    add(support, resource);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot load profile file " + file.getAbsolutePath() + ": " + e.getMessage(), e);
            }
        }
        return support;
    }

    private static void loadPackage(FhirContext ctx, File file, PrePopulatedValidationSupport support) throws IOException {
        NpmPackage npm;
        try (InputStream in = new FileInputStream(file)) {
            npm = NpmPackage.fromPackage(in);
        }
        checkVersion(ctx, npm.fhirVersionList(), "Package " + npm.vid());
        IParser parser = ctx.newJsonParser();
        for (String resourceFile : npm.listResources(CONFORMANCE_TYPES.toArray(new String[0]))) {
            try (InputStream in = npm.loadResource(resourceFile)) {
                add(support, parser.parseResource(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
    }

    private static void add(PrePopulatedValidationSupport support, IBaseResource resource) {
        if (resource != null && CONFORMANCE_TYPES.contains(resource.fhirType())) {
            support.addResource(resource);
        }
    }

    private static void collect(File file, List<File> files) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                Arrays.sort(children);
                for (File child : children) {
                    collect(child, files);
                }
            }
        } else if (file.isFile()) {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".tgz") || name.endsWith(".tar.gz") || name.endsWith(".json") || name.endsWith(".xml")) {
                files.add(file);
            }
        } else {
            throw new IllegalArgumentException("Profile path not found: " + file.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** For parsing and encoding only: the cached context stays small as long as nothing validates with it. */
    private static FhirContext context(String fhirVersion) {
        return FhirContext.forCached(version(fhirVersion));
    }

    /** Each version needs its structures and validation-resources libraries in lib/. */
    private static FhirVersionEnum version(String fhirVersion) {
        String version = fhirVersion == null ? "" : fhirVersion.trim().toUpperCase();
        switch (version) {
            case "":
            case "R4":
                return FhirVersionEnum.R4;
            case "R4B":
                return FhirVersionEnum.R4B;
            case "R5":
                return FhirVersionEnum.R5;
            default:
                throw new IllegalArgumentException("FHIR version " + fhirVersion + " is not supported. Use R4, R4B or R5.");
        }
    }

    /**
     * Throws when profiles declare another FHIR version than the channel's: HAPI would otherwise load
     * them and report confusing errors (or none) for every message. Compares major.minor, so 4.0.0
     * and 4.0.1 are both R4.
     */
    private static void checkVersion(FhirContext ctx, String declared, String what) {
        if (declared == null || declared.trim().isEmpty()) {
            return;
        }
        FhirVersionEnum expected = ctx.getVersion().getVersion();
        for (String version : declared.split(",")) {
            if (majorMinor(version).equals(majorMinor(expected.getFhirVersionString()))) {
                return;
            }
        }
        throw new IllegalArgumentException(what + " is for FHIR " + declared.trim() + ", but FHIR Version is " + expected.name() + " ("
                + expected.getFhirVersionString() + "). Use profiles for " + expected.name() + ", or set FHIR Version to the version of the profiles.");
    }

    private static String majorMinor(String version) {
        String[] parts = version.trim().split("\\.");
        return parts.length < 2 ? version.trim() : parts[0] + "." + parts[1];
    }

    private static IParser strict(IParser parser) {
        return parser.setParserErrorHandler(new StrictErrorHandler());
    }

    private static IValidationSupport.IssueSeverity severity(String value) {
        switch (value.toLowerCase()) {
            case "error":
                return IValidationSupport.IssueSeverity.ERROR;
            case "information":
                return IValidationSupport.IssueSeverity.INFORMATION;
            default:
                return IValidationSupport.IssueSeverity.WARNING;
        }
    }

    /** HAPI finds its XML and JSON implementations through the context class loader. */
    private <T> T withEngineClassLoader(Callable<T> work) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(getClass().getClassLoader());
        try {
            return work.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
