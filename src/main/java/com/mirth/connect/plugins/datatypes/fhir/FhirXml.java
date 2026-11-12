/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FHIR JSON to FHIR XML following the rules of the specification (http://hl7.org/fhir/R4/json.html),
 * without the FHIR structure definitions: property order is kept as it is, nothing is dropped or
 * added, so the transformer sees exactly what came in. Also the namespace handling for the
 * transformer: E4X is easier without the default namespace, so it can be stripped and is put back
 * when the message leaves the transformer.
 */
public final class FhirXml {

    public static final String FHIR_NS = "http://hl7.org/fhir";
    public static final String XHTML_NS = "http://www.w3.org/1999/xhtml";

    /** Why FHIR XML with a DOCTYPE is refused (see {@link #hasDoctype}). */
    public static final String DOCTYPE_REFUSED = "FHIR XML must not contain a DOCTYPE (DTD) declaration. "
            + "The FHIR specification does not allow DTDs, and they can be used for XML External Entity (XXE) attacks.";

    public enum Format {
        JSON, XML
    }

    private static final Pattern FHIR_NS_DECLARATION = Pattern.compile("\\s+xmlns\\s*=\\s*([\"'])" + Pattern.quote(FHIR_NS) + "\\1");
    private static final Pattern XML_ROOT = Pattern.compile("<([A-Za-z_][\\w.-]*)([\\s/>])");
    private static final Pattern DIV_WITHOUT_NS = Pattern.compile("<div(?![\\w.-])(?![^>]*\\bxmlns\\s*=)");
    private static final Pattern JSON_RESOURCE_TYPE = Pattern.compile("\"resourceType\"\\s*:\\s*\"([A-Za-z]+)\"");

    private FhirXml() {}

    /** JSON when the message starts with '{', XML when it starts with '<'; anything else is not FHIR. */
    public static Format detect(String message) {
        int i = firstSignificant(message);
        if (i < 0) {
            throw new IllegalArgumentException("The message is empty.");
        }
        char c = message.charAt(i);
        if (c == '{') {
            return Format.JSON;
        }
        if (c == '<') {
            if (hasDoctype(message)) {
                throw new IllegalArgumentException(DOCTYPE_REFUSED);
            }
            return Format.XML;
        }
        if (message.startsWith(JAVASCRIPT_OBJECT, i)) {
            throw new IllegalArgumentException(JAVASCRIPT_OBJECT_REFUSED);
        }
        throw new IllegalArgumentException("The message is neither FHIR JSON (starting with '{') nor FHIR XML (starting with '<').");
    }

    /** What a JavaScript object turns into when a script passes it where text is expected. */
    private static final String JAVASCRIPT_OBJECT = "[object ";

    static final String JAVASCRIPT_OBJECT_REFUSED = "The message is a JavaScript object, not FHIR text. With Transformer Format JSON, msg is an object: "
            + "pass JSON.stringify(msg) instead of msg or msg.toString().";

    /** The message as XML for the transformer: JSON is converted, XML kept; optionally without the FHIR namespace. */
    public static String toTransformerXml(String message, boolean stripNamespace) {
        String xml = detect(message) == Format.JSON ? jsonToXml(message, !stripNamespace) : message.trim();
        return stripNamespace ? stripNamespace(xml) : xml;
    }

    /** FHIR JSON to FHIR XML, pretty printed. */
    public static String jsonToXml(String json, boolean withNamespace) {
        Object root = FhirJson.parse(json);
        if (!(root instanceof Map) || !(((Map<?, ?>) root).get("resourceType") instanceof String)) {
            throw new IllegalArgumentException("The JSON is not a FHIR resource: it has no \"resourceType\".");
        }
        StringBuilder sb = new StringBuilder();
        writeResource(sb, cast(root), 0, withNamespace);
        return sb.toString();
    }

    /** Removes the FHIR default namespace declarations; the XHTML namespace of narratives stays. */
    public static String stripNamespace(String xml) {
        return FHIR_NS_DECLARATION.matcher(xml).replaceAll("");
    }

    /**
     * Puts the FHIR namespace back on the root element when it has none, and the XHTML namespace on
     * narrative divs that lost theirs (for example when a transformer created one).
     */
    public static String addNamespace(String xml) {
        String out = DIV_WITHOUT_NS.matcher(xml).replaceAll(Matcher.quoteReplacement("<div xmlns=\"" + XHTML_NS + "\""));
        int root = rootStart(out);
        if (root < 0) {
            return out;
        }
        int end = out.indexOf('>', root);
        if (end < 0 || out.substring(root, end).matches("(?s).*\\sxmlns\\s*=.*")) {
            return out;
        }
        Matcher m = XML_ROOT.matcher(out);
        if (!m.find(root) || m.start() != root) {
            return out;
        }
        int insert = root + 1 + m.group(1).length();
        return out.substring(0, insert) + " xmlns=\"" + FHIR_NS + "\"" + out.substring(insert);
    }

    /** The resource type (e.g. Patient) of a JSON or XML message, or null. Cheap: no full parse. */
    public static String resourceType(String message) {
        try {
            if (detect(message) == Format.JSON) {
                Matcher m = JSON_RESOURCE_TYPE.matcher(message);
                return m.find() ? m.group(1) : null;
            }
            int root = rootStart(message);
            if (root < 0) {
                return null;
            }
            Matcher m = XML_ROOT.matcher(message);
            if (m.find(root) && m.start() == root) {
                String name = m.group(1);
                int colon = name.indexOf(':');
                return colon >= 0 ? name.substring(colon + 1) : name;
            }
        } catch (RuntimeException e) {
            // not FHIR; the caller reports that
        }
        return null;
    }

    // ------------------------------------------------------------------ JSON to XML

    private static void writeResource(StringBuilder sb, Map<String, Object> resource, int depth, boolean withNamespace) {
        String type = (String) resource.get("resourceType");
        indent(sb, depth);
        sb.append('<').append(type);
        if (withNamespace) {
            sb.append(" xmlns=\"").append(FHIR_NS).append('"');
        }
        StringBuilder children = new StringBuilder();
        writeProperties(children, resource, depth + 1, true, false);
        close(sb, type, children, depth);
    }

    /**
     * @param resource whether the object is a resource: there 'id' is an element, in a data type or backbone element an attribute
     * @param extension whether the object is an extension: its 'url' is an attribute
     */
    private static void writeProperties(StringBuilder sb, Map<String, Object> object, int depth, boolean resource, boolean extension) {
        for (Map.Entry<String, Object> e : object.entrySet()) {
            String name = e.getKey();
            if (name.equals("resourceType") || (!resource && name.equals("id")) || (extension && name.equals("url"))) {
                continue;
            }
            if (name.startsWith("_")) {
                String base = name.substring(1);
                if (!object.containsKey(base)) {
                    // A primitive with only an id and/or extensions, and no value.
                    writeValues(sb, base, FhirJson.NULL, e.getValue(), depth);
                }
                continue;
            }
            writeValues(sb, name, e.getValue(), object.get("_" + name), depth);
        }
    }

    private static void writeValues(StringBuilder sb, String name, Object value, Object primitiveExtension, int depth) {
        if (value instanceof List || primitiveExtension instanceof List) {
            List<?> values = value instanceof List ? (List<?>) value : null;
            List<?> extensions = primitiveExtension instanceof List ? (List<?>) primitiveExtension : null;
            int n = Math.max(values == null ? 0 : values.size(), extensions == null ? 0 : extensions.size());
            for (int i = 0; i < n; i++) {
                Object v = values != null && i < values.size() ? values.get(i) : FhirJson.NULL;
                Object x = extensions != null && i < extensions.size() ? extensions.get(i) : null;
                writeValue(sb, name, v, x, depth);
            }
        } else {
            writeValue(sb, name, value, primitiveExtension, depth);
        }
    }

    private static void writeValue(StringBuilder sb, String name, Object value, Object primitiveExtension, int depth) {
        if (value instanceof Map) {
            Map<String, Object> object = cast(value);
            if (object.get("resourceType") instanceof String) {
                indent(sb, depth);
                sb.append('<').append(name).append(">\n");
                writeResource(sb, object, depth + 1, false);
                indent(sb, depth);
                sb.append("</").append(name).append(">\n");
                return;
            }
            boolean extension = name.equals("extension") || name.equals("modifierExtension");
            indent(sb, depth);
            sb.append('<').append(name);
            if (object.get("id") instanceof String) {
                attribute(sb, "id", (String) object.get("id"));
            }
            if (extension && object.get("url") instanceof String) {
                attribute(sb, "url", (String) object.get("url"));
            }
            StringBuilder children = new StringBuilder();
            writeProperties(children, object, depth + 1, false, extension);
            close(sb, name, children, depth);
            return;
        }
        if (name.equals("div") && value instanceof String && ((String) value).trim().startsWith("<")) {
            // The narrative: XHTML, embedded as it is.
            String div = ((String) value).trim();
            if (!div.matches("(?s)<div\\b[^>]*\\bxmlns\\s*=.*")) {
                div = div.replaceFirst("^<div\\b", Matcher.quoteReplacement("<div xmlns=\"" + XHTML_NS + "\""));
            }
            indent(sb, depth);
            sb.append(div).append('\n');
            return;
        }
        if (value == FhirJson.NULL && primitiveExtension == null) {
            return;
        }
        if (value instanceof List || value instanceof Map) {
            throw new IllegalArgumentException("Unexpected nested array in \"" + name + "\".");
        }
        indent(sb, depth);
        sb.append('<').append(name);
        Map<String, Object> ext = primitiveExtension instanceof Map ? cast(primitiveExtension) : null;
        if (ext != null && ext.get("id") instanceof String) {
            attribute(sb, "id", (String) ext.get("id"));
        }
        if (value != null && value != FhirJson.NULL) {
            attribute(sb, "value", value.toString());
        }
        StringBuilder children = new StringBuilder();
        if (ext != null) {
            writeProperties(children, ext, depth + 1, false, false);
        }
        close(sb, name, children, depth);
    }

    private static void close(StringBuilder sb, String name, StringBuilder children, int depth) {
        if (children.length() == 0) {
            sb.append("/>\n");
        } else {
            sb.append(">\n").append(children);
            indent(sb, depth);
            sb.append("</").append(name).append(">\n");
        }
    }

    private static void attribute(StringBuilder sb, String name, String value) {
        sb.append(' ').append(name).append("=\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                // Kept as character references: a parser would turn raw ones in an attribute into spaces.
                case '\n': sb.append("&#xA;"); break;
                case '\r': sb.append("&#xD;"); break;
                case '\t': sb.append("&#x9;"); break;
                default: sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    /**
     * True when the XML prolog (before the root element) has a DOCTYPE. FHIR XML may not contain a
     * DTD, and a DTD is how XML External Entity (XXE) attacks get in, so such a message is refused
     * before anything parses it. A "<!DOCTYPE" inside the resource (e.g. in a comment) does not count.
     */
    public static boolean hasDoctype(String xml) {
        if (xml == null) {
            return false;
        }
        int i = 0;
        while (true) {
            i = xml.indexOf('<', i);
            if (i < 0 || i + 1 >= xml.length()) {
                return false;
            }
            if (xml.charAt(i + 1) == '?') {
                i = xml.indexOf("?>", i);
            } else if (xml.startsWith("<!--", i)) {
                i = xml.indexOf("-->", i);
            } else {
                // The first other markup is either the DOCTYPE or the root element.
                return xml.regionMatches(true, i, "<!DOCTYPE", 0, 9);
            }
            if (i < 0) {
                return false;
            }
            i++;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Position of the root element's '<', after an XML declaration, comments and processing instructions. */
    private static int rootStart(String xml) {
        int i = 0;
        while (true) {
            i = xml.indexOf('<', i);
            if (i < 0 || i + 1 >= xml.length()) {
                return -1;
            }
            char c = xml.charAt(i + 1);
            if (c == '?') {
                i = xml.indexOf("?>", i);
            } else if (xml.startsWith("<!--", i)) {
                i = xml.indexOf("-->", i);
            } else if (c == '!') {
                i = xml.indexOf('>', i);
            } else {
                return i;
            }
            if (i < 0) {
                return -1;
            }
            i++;
        }
    }

    private static int firstSignificant(String s) {
        if (s == null) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '\uFEFF') {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }
}
