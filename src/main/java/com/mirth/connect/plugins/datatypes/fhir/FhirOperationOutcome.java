/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds FHIR OperationOutcome resources as JSON (the same structure in R4, R4B and R5), without HAPI, so they can be created
 * anywhere (and turned into XML with {@link FhirXml#jsonToXml}). Issue elements are written in
 * the order of the specification, so the XML form is valid as well.
 */
public final class FhirOperationOutcome {

    private static final String LINE_EXTENSION = "http://hl7.org/fhir/StructureDefinition/operationoutcome-issue-line";
    private static final String COLUMN_EXTENSION = "http://hl7.org/fhir/StructureDefinition/operationoutcome-issue-col";

    private FhirOperationOutcome() {}

    /** The validation issues; a single informational issue when there are none. */
    public static String fromValidation(FhirValidation validation) {
        List<Map<String, String>> issues = validation.getIssues();
        if (issues.isEmpty()) {
            return information("The message is valid.");
        }
        StringBuilder sb = new StringBuilder("{\"resourceType\":\"OperationOutcome\",\"issue\":[");
        boolean first = true;
        for (Map<String, String> issue : issues) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendIssue(sb, issue.get(FhirEngine.SEVERITY), "processing", issue.get(FhirEngine.MESSAGE), issue.get(FhirEngine.LOCATION), issue.get(FhirEngine.LINE), issue.get(FhirEngine.COLUMN));
        }
        return sb.append("]}").toString();
    }

    /** One issue with severity error and the given code, e.g. exception. */
    public static String error(String code, String diagnostics) {
        return single("error", code, diagnostics);
    }

    /** One informational issue. */
    public static String information(String diagnostics) {
        return single("information", "informational", diagnostics);
    }

    private static String single(String severity, String code, String diagnostics) {
        StringBuilder sb = new StringBuilder("{\"resourceType\":\"OperationOutcome\",\"issue\":[");
        appendIssue(sb, severity, code, diagnostics, null, null, null);
        return sb.append("]}").toString();
    }

    /** extension, severity, code, diagnostics, location, expression: the order of OperationOutcome.issue. */
    private static void appendIssue(StringBuilder sb, String severity, String code, String diagnostics, String location, String line, String column) {
        sb.append('{');
        List<String> extensions = new ArrayList<>();
        if (isNumber(line)) {
            extensions.add("{\"url\":\"" + LINE_EXTENSION + "\",\"valueInteger\":" + line + "}");
        }
        if (isNumber(column)) {
            extensions.add("{\"url\":\"" + COLUMN_EXTENSION + "\",\"valueInteger\":" + column + "}");
        }
        if (!extensions.isEmpty()) {
            sb.append("\"extension\":[").append(String.join(",", extensions)).append("],");
        }
        sb.append("\"severity\":").append(string(severity(severity)));
        sb.append(",\"code\":").append(string(code));
        if (diagnostics != null && !diagnostics.isEmpty()) {
            sb.append(",\"diagnostics\":").append(string(diagnostics));
        }
        if (location != null && !location.isEmpty()) {
            // HAPI reports FHIRPath (Patient.gender) for JSON and XPath (/f:Patient/f:gender) for XML.
            boolean xpath = location.startsWith("/");
            sb.append(xpath ? ",\"location\":[" : ",\"expression\":[").append(string(location)).append(']');
        }
        sb.append('}');
    }

    private static String severity(String severity) {
        if ("fatal".equals(severity) || "error".equals(severity) || "warning".equals(severity) || "information".equals(severity)) {
            return severity;
        }
        return "error";
    }

    private static boolean isNumber(String value) {
        return value != null && value.matches("\\d{1,9}");
    }

    /** A JSON string literal. */
    static String string(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
