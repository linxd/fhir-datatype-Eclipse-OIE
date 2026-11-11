/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Loads the FHIR engine (HAPI FHIR and its dependencies) from the extension's lib folder in its own
 * child-first class loader, so none of its libraries (Jackson, Guava, commons-*, ...) can clash with
 * the versions on the engine's classpath. Only {@link FhirEngine} and the JDK are shared.
 *
 * The lib folder only exists on the server; in the Administrator the engine is not available, which
 * only matters for validation and XML-to-JSON conversion, both of which run on the server.
 */
public final class FhirEngineLoader {

    private static final String ENGINE_CLASS = "com.mirth.connect.plugins.datatypes.fhir.engine.HapiFhirEngine";

    /**
     * Always from the parent: the JDK, the interface the engine implements and the engine's Log4j,
     * which HAPI's SLF4J logging is routed to (lib/ has the SLF4J-to-Log4j provider).
     */
    private static final String[] PARENT_FIRST = { "java.", "javax.xml.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.", "org.apache.logging.log4j.", FhirEngine.class.getName() };

    private static FhirEngine engine;
    private static String error;

    private FhirEngineLoader() {}

    /** The engine, loaded on first use. Throws with the reason when it cannot be loaded. */
    public static synchronized FhirEngine engine() {
        if (engine == null && error == null) {
            try {
                engine = load();
                // Started here, so idle validators are released whoever built them (a FHIR channel,
                // FhirTools in a script, or another plugin using the engine).
                FhirValidatorUsage.startPeriodicRelease(engine);
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            }
        }
        if (engine == null) {
            throw new IllegalStateException("The FHIR engine is not available (" + error + ").");
        }
        return engine;
    }

    /** The engine if it was loaded already, otherwise null; never loads it. */
    public static synchronized FhirEngine loadedEngine() {
        return engine;
    }

    /** Whether this runs in the server (and not the Administrator), where the engine must be available. */
    public static boolean isServer() {
        try {
            Class.forName("com.mirth.connect.server.controllers.ControllerFactory", false, FhirEngineLoader.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static FhirEngine load() throws Exception {
        if (Boolean.getBoolean("fhir.engine.direct")) {
            // Tests: HAPI is on the classpath already.
            return (FhirEngine) Class.forName(ENGINE_CLASS).getConstructor().newInstance();
        }
        File libDir = libDir();
        File[] jars = libDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new IOException("no libraries in " + libDir.getAbsolutePath());
        }
        List<URL> urls = new ArrayList<>();
        for (File jar : jars) {
            urls.add(jar.toURI().toURL());
        }
        ClassLoader loader = new ChildFirstClassLoader(urls.toArray(new URL[0]), FhirEngineLoader.class.getClassLoader());
        return (FhirEngine) Class.forName(ENGINE_CLASS, true, loader).getConstructor().newInstance();
    }

    /**
     * extensions/datatype-fhir/lib, next to the jar this class was loaded from. The system property
     * fhir.engine.lib overrides it.
     */
    private static File libDir() throws IOException {
        String override = System.getProperty("fhir.engine.lib");
        if (override != null && !override.isEmpty()) {
            return new File(override);
        }
        try {
            File jar = new File(FhirEngineLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return new File(jar.isDirectory() ? jar : jar.getParentFile(), "lib");
        } catch (Exception e) {
            throw new IOException("cannot determine the folder of the FHIR data type extension", e);
        }
    }

    private static final class ChildFirstClassLoader extends URLClassLoader {

        static {
            registerAsParallelCapable();
        }

        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (parentFirst(name)) {
                        c = getParent().loadClass(name);
                    } else {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            c = getParent().loadClass(name);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        @Override
        public URL getResource(String name) {
            URL url = findResource(name);
            return url != null ? url : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            // Own resources first (the FHIR specification, service files), then the parent's.
            List<URL> all = new ArrayList<>(Collections.list(findResources(name)));
            ClassLoader parent = getParent();
            if (parent != null && !name.startsWith("META-INF/services/")) {
                all.addAll(Collections.list(parent.getResources(name)));
            }
            return Collections.enumeration(all);
        }

        private static boolean parentFirst(String name) {
            for (String prefix : PARENT_FIRST) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
