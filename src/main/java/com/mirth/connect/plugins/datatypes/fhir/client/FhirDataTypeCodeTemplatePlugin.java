/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateContextSet;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.codetemplates.CodeTemplateProperties.CodeTemplateType;
import com.mirth.connect.model.codetemplates.ContextType;
import com.mirth.connect.model.datatype.DataTypeDelegate;
import com.mirth.connect.plugins.DataTypeCodeTemplatePlugin;
import com.mirth.connect.plugins.datatypes.fhir.FhirDataTypeDelegate;

/**
 * Adds the standard conversion templates and a "FHIR Functions" category to the reference list
 * of the script editors: FhirTools, the validation variables and E4X patterns for FHIR XML.
 */
public class FhirDataTypeCodeTemplatePlugin extends DataTypeCodeTemplatePlugin {

    public static final String CATEGORY = "FHIR Functions";

    private static final String FHIR_TOOLS = "var FhirTools = Packages.com.mirth.connect.plugins.datatypes.fhir.FhirTools;\n";

    private static final String STRIP_NAMESPACES_NOTE = " Assumes Strip Namespaces is on (the default), so the FHIR XML has no namespace.";

    public FhirDataTypeCodeTemplatePlugin(String name) {
        super(name);
    }

    @Override
    protected DataTypeDelegate getDataTypeDelegate() {
        return new FhirDataTypeDelegate();
    }

    @Override
    protected String getDisplayName() {
        return "FHIR";
    }

    @Override
    public Map<String, List<CodeTemplate>> getReferenceItems() {
        Map<String, List<CodeTemplate>> referenceItems = super.getReferenceItems();
        referenceItems.put(CATEGORY, fhirTemplates());
        return referenceItems;
    }

    static List<CodeTemplate> fhirTemplates() {
        CodeTemplateContextSet everywhere = CodeTemplateContextSet.getGlobalContextSet();
        CodeTemplateContextSet connector = CodeTemplateContextSet.getConnectorContextSet();
        CodeTemplateContextSet filterTransformer = new CodeTemplateContextSet(ContextType.SOURCE_FILTER_TRANSFORMER, ContextType.DESTINATION_FILTER_TRANSFORMER, ContextType.DESTINATION_RESPONSE_TRANSFORMER);

        List<CodeTemplate> templates = new ArrayList<CodeTemplate>();

        // FhirTools
        templates.add(code("Validate FHIR resource", everywhere,
                FHIR_TOOLS
                + "var result = FhirTools.validate(msg.toString());\n"
                + "if (!result.isValid()) {\n"
                + "    logger.warn('Invalid FHIR resource:\\n' + result.summary());\n"
                + "}",
                "Validates a FHIR resource (JSON, or XML with or without the FHIR namespace) against the FHIR R4 specification and its meta.profile. isValid() is false on errors; summary() gives one line per error and warning. For R4B or R5, pass the version as fourth argument: FhirTools.validate(resource, '', '', 'R5')."));
        templates.add(code("Validate FHIR resource against a profile", everywhere,
                FHIR_TOOLS
                + "var result = FhirTools.validate(msg.toString(), 'http://example.org/fhir/StructureDefinition/my-patient', 'C:/fhir/packages');\n"
                + "if (!result.isValid()) {\n"
                + "    logger.warn('Resource does not conform to the profile:\\n' + result.summary());\n"
                + "}",
                "Validates a FHIR resource against a required profile (canonical URL). The third argument lists files and folders with your own profiles and NPM packages, separated by ';' (empty for none). An optional fourth argument sets the FHIR version: R4 (default), R4B or R5; the packages must be for that version."));
        templates.add(code("Read FHIR validation issues", everywhere,
                FHIR_TOOLS
                + "var result = FhirTools.validate(msg.toString());\n"
                + "var issues = result.getProblems();\n"
                + "for (var i = 0; i < issues.size(); i++) {\n"
                + "    var issue = issues.get(i);\n"
                + "    logger.info(issue.get('severity') + ' ' + issue.get('location') + ': ' + issue.get('message'));\n"
                + "}",
                "Walks through the errors and warnings of a validation. Each issue has severity, location, line, column, message and messageId. getIssues() also includes information messages."));
        templates.add(code("Convert FHIR XML to JSON", everywhere,
                FHIR_TOOLS + "var json = FhirTools.toJson(msg.toString());",
                "Converts FHIR XML, with or without the FHIR namespace (e.g. the transformer's msg), to FHIR JSON. Uses R4; for R4B or R5 pass the version: FhirTools.toJson(xml, 'R5')."));
        templates.add(code("Convert FHIR JSON to XML", everywhere,
                FHIR_TOOLS + "var xml = FhirTools.toXml(json);",
                "Converts FHIR JSON to FHIR XML with the FHIR namespace. Use new XML(...) on the result to work on it with E4X. Uses R4; for R4B or R5 pass the version: FhirTools.toXml(json, 'R5')."));

        // Validation results of the inbound data type
        templates.add(code("Is the FHIR message valid", connector, "$('fhirValid')",
                "true or false: the result of the inbound validation, set also when Invalid Messages is on Accept."));
        templates.add(code("Get FHIR issue count", connector, "$('fhirIssueCount')",
                "The number of errors and warnings the inbound validation found."));
        templates.add(code("Get FHIR issues", connector, "$('fhirIssues')",
                "The errors and warnings of the inbound validation, one line per issue, e.g. 'error Patient.gender (line 1, column 39): ...'."));
        templates.add(code("Get FHIR OperationOutcome", connector, "$('fhirOperationOutcome')",
                "The issues of the inbound validation as a FHIR OperationOutcome resource (JSON), ready to return to the sender."));
        templates.add(code("Filter out invalid FHIR messages", connector,
                "if ($('fhirValid') == false) {\n"
                + "    logger.warn('Invalid FHIR message:\\n' + $('fhirIssues'));\n"
                + "    return false;\n"
                + "}\n"
                + "return true;",
                "A filter rule that only lets valid messages through. Set Invalid Messages to Accept, otherwise invalid messages are already rejected before the filter."));

        // E4X on FHIR XML
        templates.add(code("Read a FHIR value", filterTransformer,
                "var family = msg.name[0].family.@value.toString();",
                "FHIR XML keeps values in the value attribute: read them with @value and toString()." + STRIP_NAMESPACES_NOTE));
        templates.add(code("Set a FHIR value", filterTransformer,
                "msg.gender.@value = 'female';",
                "Sets the value attribute of an existing element." + STRIP_NAMESPACES_NOTE));
        templates.add(code("Add a FHIR element", filterTransformer,
                "msg.appendChild(<birthDate value=\"1980-01-01\"/>);",
                "Adds an element with a value attribute. The place does not matter: the outbound message is written in the element order of the specification." + STRIP_NAMESPACES_NOTE));
        templates.add(code("Iterate FHIR Bundle entries", filterTransformer,
                "for each (var entry in msg.entry) {\n"
                + "    var resource = entry.resource.children()[0];\n"
                + "    logger.info(resource.localName() + '/' + resource.id.@value);\n"
                + "}",
                "Walks through the entries of a Bundle. Each entry.resource holds one resource element, e.g. <Patient>." + STRIP_NAMESPACES_NOTE));
        templates.add(code("Find a FHIR coding by system", filterTransformer,
                "var loinc = msg.code.coding.(system.@value == 'http://loinc.org').code.@value.toString();",
                "Picks the code of the coding with a given system from a CodeableConcept." + STRIP_NAMESPACES_NOTE));
        templates.add(code("Find a FHIR extension by URL", filterTransformer,
                "var extension = msg.extension.(@url == 'http://example.org/fhir/StructureDefinition/my-extension');",
                "Selects an extension by its url attribute; read its value with e.g. extension.valueString.@value." + STRIP_NAMESPACES_NOTE));

        return templates;
    }

    /**
     * The same templates as an importable code template library, for the web administrator: its
     * reference list shows code template libraries but has no way for plugins to add items.
     * Written to examples/ by FhirDataTypeCodeTemplatePluginTest. IDs and dates are fixed so the
     * file only changes when the templates do.
     */
    public static CodeTemplateLibrary fhirLibrary() {
        Calendar fixed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        fixed.clear();
        fixed.set(2026, Calendar.OCTOBER, 1);

        List<CodeTemplate> templates = fhirTemplates();
        for (CodeTemplate template : templates) {
            template.setId(stableId("template:" + template.getName()));
            template.setRevision(1);
            template.setLastModified((Calendar) fixed.clone());
        }

        CodeTemplateLibrary library = new CodeTemplateLibrary();
        library.setId(stableId("library:" + CATEGORY));
        library.setName(CATEGORY);
        library.setRevision(1);
        library.setLastModified(fixed);
        library.setDescription("Snippets for the FHIR Data Type (datatype-fhir): FhirTools, the inbound validation variables and E4X on FHIR XML. "
                + "The Swing Administrator already shows these under FHIR Functions; import this library for the web administrator's reference list.");
        // The web administrator only lists libraries that apply to the channel. Drag-and-drop
        // templates are never added to scripts, so including all channels costs nothing.
        library.setIncludeNewChannels(true);
        library.setCodeTemplates(templates);
        return library;
    }

    private static String stableId(String key) {
        return UUID.nameUUIDFromBytes(("datatype-fhir:" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static CodeTemplate code(String name, CodeTemplateContextSet contexts, String code, String description) {
        return new CodeTemplate(name, CodeTemplateType.DRAG_AND_DROP_CODE, contexts, code, description);
    }
}
