/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Splits a FHIR Bundle (JSON or XML) into one message per Bundle.entry.resource, in the format it
 * came in. Used by the batch adaptor ("Split Batch By: Bundle entry"); no HAPI involved.
 *
 * A message that is not a Bundle, or that cannot be read, stays one message as it is, so the data
 * type's validation reports what is wrong with it. Entries without a resource (a DELETE in a
 * transaction) give no message.
 */
public final class FhirBundleSplitter {

    /** Source map keys of every message split from a Bundle. */
    public static final String BUNDLE_TYPE = "fhirBundleType";
    public static final String BUNDLE_ID = "fhirBundleId";
    public static final String ENTRY_INDEX = "fhirEntryIndex";
    public static final String ENTRY_COUNT = "fhirEntryCount";
    public static final String ENTRY_FULL_URL = "fhirEntryFullUrl";
    public static final String ENTRY_REQUEST_METHOD = "fhirEntryRequestMethod";
    public static final String ENTRY_REQUEST_URL = "fhirEntryRequestUrl";

    /** One message: the resource and what the source map gets with it (empty when not from a Bundle). */
    public static final class Part {
        private final String message;
        private final Map<String, Object> sourceMap;

        Part(String message, Map<String, Object> sourceMap) {
            this.message = message;
            this.sourceMap = Collections.unmodifiableMap(sourceMap);
        }

        public String getMessage() {
            return message;
        }

        public Map<String, Object> getSourceMap() {
            return sourceMap;
        }
    }

    private FhirBundleSplitter() {}

    public static List<Part> split(String message) {
        if (message == null || message.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<Part> parts = FhirXml.detect(message) == FhirXml.Format.JSON ? splitJson(message) : splitXml(message);
            if (parts != null) {
                return parts;
            }
        } catch (Exception e) {
            // not readable: one message, so validation reports why
        }
        return Collections.singletonList(new Part(message.trim(), new LinkedHashMap<String, Object>()));
    }

    // ------------------------------------------------------------------ JSON

    private static List<Part> splitJson(String json) {
        Object root = FhirJson.parse(json);
        if (!(root instanceof Map) || !"Bundle".equals(((Map<?, ?>) root).get("resourceType"))) {
            return null;
        }
        Map<?, ?> bundle = (Map<?, ?>) root;
        Object entries = bundle.get("entry");
        List<?> list = entries instanceof List ? (List<?>) entries : Collections.emptyList();
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map)) {
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) list.get(i);
            if (!(entry.get("resource") instanceof Map)) {
                continue;
            }
            Map<?, ?> request = entry.get("request") instanceof Map ? (Map<?, ?>) entry.get("request") : Collections.emptyMap();
            Map<String, Object> sourceMap = sourceMap(text(bundle.get("type")), text(bundle.get("id")), i, list.size(),
                    text(entry.get("fullUrl")), text(request.get("method")), text(request.get("url")));
            parts.add(new Part(FhirJson.write(entry.get("resource")), sourceMap));
        }
        return parts;
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    // ------------------------------------------------------------------ XML

    private static List<Part> splitXml(String xml) throws Exception {
        Document doc = parse(xml);
        Element bundle = doc.getDocumentElement();
        if (!"Bundle".equals(localName(bundle))) {
            return null;
        }
        List<Element> entries = children(bundle, "entry");
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Element entry = entries.get(i);
            Element resourceElement = child(entry, "resource");
            Element resource = resourceElement == null ? null : firstElement(resourceElement);
            if (resource == null) {
                continue;
            }
            Element request = child(entry, "request");
            Map<String, Object> sourceMap = sourceMap(value(child(bundle, "type")), value(child(bundle, "id")), i, entries.size(),
                    value(child(entry, "fullUrl")), value(child(request, "method")), value(child(request, "url")));
            parts.add(new Part(FhirXml.addNamespace(write(resource)), sourceMap));
        }
        return parts;
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(new InputSource(new StringReader(xml.trim())));
    }

    /** The element as text, without the indentation it had inside the Bundle. */
    private static String write(Element resource) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(resource), new StreamResult(out));
        // the parser made every line end a \n; the serializer writes the platform's line separator
        String text = out.toString().replace(System.lineSeparator(), "\n");
        String indent = indentBefore(resource);
        return indent.isEmpty() ? text : text.replace("\n" + indent, "\n");
    }

    private static String indentBefore(Element element) {
        Node previous = element.getPreviousSibling();
        if (previous == null || previous.getNodeType() != Node.TEXT_NODE) {
            return "";
        }
        String text = previous.getNodeValue();
        int newline = text.lastIndexOf('\n');
        String tail = newline < 0 ? "" : text.substring(newline + 1);
        return tail.trim().isEmpty() ? tail : "";
    }

    static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    static List<Element> children(Element parent, String name) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(n))) {
                list.add((Element) n);
            }
        }
        return list;
    }

    static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        List<Element> list = children(parent, name);
        return list.isEmpty() ? null : list.get(0);
    }

    static Element firstElement(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String value(Element element) {
        return element == null || !element.hasAttribute("value") ? null : element.getAttribute("value");
    }

    // ------------------------------------------------------------------ source map

    private static Map<String, Object> sourceMap(String bundleType, String bundleId, int index, int count, String fullUrl, String method, String url) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, BUNDLE_TYPE, bundleType);
        put(map, BUNDLE_ID, bundleId);
        map.put(ENTRY_INDEX, index);
        map.put(ENTRY_COUNT, count);
        put(map, ENTRY_FULL_URL, fullUrl);
        put(map, ENTRY_REQUEST_METHOD, method);
        put(map, ENTRY_REQUEST_URL, url);
        return map;
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
