/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

/**
 * The <i>Resource Types</i> property: the resource types a connector accepts. A message whose type,
 * or the type of one of its Bundle entries, is not in the list is refused with an error issue, which
 * <i>Invalid Messages</i> treats like a validation error. An empty list accepts every type.
 *
 * The check is done before validation and does not change the validator: the validator asks for all
 * StructureDefinitions of the specification at once, whatever the message contains, so a smaller
 * list would not use less memory (see docs/lazy-loading-measurement.md).
 */
final class FhirResourceTypes {

    /** The messageId of the issue, so scripts can tell it from validation issues. */
    static final String MESSAGE_ID = "FHIR_DATATYPE_RESOURCE_TYPE_NOT_ALLOWED";

    private FhirResourceTypes() {}

    /** The types in the property: separated by commas, semicolons or white space; duplicates and empty items dropped. */
    static Set<String> parse(String property) {
        Set<String> types = new LinkedHashSet<>();
        if (property != null) {
            for (String type : property.split("[,;\\s]+")) {
                if (!type.isEmpty()) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    /**
     * An error issue for every resource in the message whose type is not allowed: the resource itself,
     * and the resources of its Bundle entries (also of a Bundle inside an entry). Empty when all are
     * allowed, when the list is empty, and when the message cannot be read (validation reports that).
     */
    static List<Map<String, String>> check(String message, Set<String> allowed) {
        if (allowed.isEmpty()) {
            return Collections.emptyList();
        }
        String rootType = FhirXml.resourceType(message);
        if (rootType == null) {
            return Collections.emptyList();
        }
        List<Map<String, String>> issues = new ArrayList<>();
        if (!allowed.contains(rootType)) {
            issues.add(issue(rootType, rootType, allowed));
        }
        if ("Bundle".equals(rootType)) {
            try {
                if (FhirXml.detect(message) == FhirXml.Format.JSON) {
                    Object root = FhirJson.parse(message);
                    if (root instanceof Map) {
                        checkJsonEntries((Map<?, ?>) root, "Bundle", allowed, issues);
                    }
                } else {
                    checkXmlEntries(FhirBundleSplitter.parse(message).getDocumentElement(), "Bundle", allowed, issues);
                }
            } catch (Exception e) {
                // not readable: validation (or the conversion) reports why
            }
        }
        return issues;
    }

    private static void checkJsonEntries(Map<?, ?> bundle, String path, Set<String> allowed, List<Map<String, String>> issues) {
        Object entries = bundle.get("entry");
        if (!(entries instanceof List)) {
            return;
        }
        List<?> list = (List<?>) entries;
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            Object resource = entry instanceof Map ? ((Map<?, ?>) entry).get("resource") : null;
            if (!(resource instanceof Map) || !(((Map<?, ?>) resource).get("resourceType") instanceof String)) {
                continue;
            }
            String type = (String) ((Map<?, ?>) resource).get("resourceType");
            String location = path + ".entry[" + i + "].resource";
            if (!allowed.contains(type)) {
                issues.add(issue(type, location, allowed));
            }
            if ("Bundle".equals(type)) {
                checkJsonEntries((Map<?, ?>) resource, location, allowed, issues);
            }
        }
    }

    private static void checkXmlEntries(Element bundle, String path, Set<String> allowed, List<Map<String, String>> issues) {
        List<Element> entries = FhirBundleSplitter.children(bundle, "entry");
        for (int i = 0; i < entries.size(); i++) {
            Element wrapper = FhirBundleSplitter.child(entries.get(i), "resource");
            Element resource = wrapper == null ? null : FhirBundleSplitter.firstElement(wrapper);
            if (resource == null) {
                continue;
            }
            String type = FhirBundleSplitter.localName(resource);
            String location = path + ".entry[" + i + "].resource";
            if (!allowed.contains(type)) {
                issues.add(issue(type, location, allowed));
            }
            if ("Bundle".equals(type)) {
                checkXmlEntries(resource, location, allowed, issues);
            }
        }
    }

    private static Map<String, String> issue(String type, String location, Set<String> allowed) {
        Map<String, String> issue = new LinkedHashMap<>();
        issue.put(FhirEngine.SEVERITY, "error");
        issue.put(FhirEngine.LOCATION, location);
        issue.put(FhirEngine.LINE, "");
        issue.put(FhirEngine.COLUMN, "");
        issue.put(FhirEngine.MESSAGE, "Resource type " + type + " is not in Resource Types (" + String.join(", ", allowed) + ")");
        issue.put(FhirEngine.MESSAGE_ID, MESSAGE_ID);
        return issue;
    }
}
