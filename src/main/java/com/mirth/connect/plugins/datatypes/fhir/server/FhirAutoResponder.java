/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir.server;

import java.util.Map;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.message.AutoResponder;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirOperationOutcome;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.InvalidMessages;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializer;
import com.mirth.connect.plugins.datatypes.fhir.FhirXml;

/**
 * The source response for "Auto-generate": a FHIR OperationOutcome, in the format of the message
 * that came in (JSON or XML).
 *
 * <ul>
 * <li>Rejected as invalid: the validation issues, HTTP status 400.</li>
 * <li>Any other error: one issue with the error, HTTP status 500.</li>
 * <li>Accepted: the validation issues (warnings, or errors when invalid messages are accepted), or one
 * informational issue, HTTP status 200.</li>
 * </ul>
 *
 * The HTTP status and content type go into the channel map and the source map as fhirHttpStatus and fhirContentType,
 * for the HTTP Listener's Response Status Code and Response Content Type (${fhirHttpStatus},
 * ${fhirContentType}). Without them the listener answers 500 for errors and 200 otherwise.
 */
public class FhirAutoResponder implements AutoResponder {

    public static final String HTTP_STATUS_VARIABLE = "fhirHttpStatus";
    public static final String CONTENT_TYPE_VARIABLE = "fhirContentType";

    private final FhirSerializationProperties properties;
    private final FhirSerializer serializer;

    public FhirAutoResponder(FhirSerializationProperties properties) {
        this.properties = properties != null ? properties : new FhirSerializationProperties();
        this.serializer = new FhirSerializer(new SerializerProperties(this.properties, null, null));
    }

    @Override
    public Response getResponse(Status status, String message, ConnectorMessage connectorMessage) throws Exception {
        Map<String, Object> connectorMap = connectorMessage.getConnectorMap();
        if (serializer.checksInbound() && !connectorMap.containsKey(FhirSerializer.VALID_VARIABLE)) {
            // "Before processing": the message has not been through the data type yet.
            serializer.populateMetaData(message, connectorMap);
        }
        Object valid = connectorMap.get(FhirSerializer.VALID_VARIABLE);
        Object outcome = connectorMap.get(FhirSerializer.OPERATION_OUTCOME_VARIABLE);
        boolean invalid = Boolean.FALSE.equals(valid);

        String body;
        int httpStatus;
        Status responseStatus;
        String statusMessage;
        String error = null;
        if (invalid && properties.getInvalidMessages() == InvalidMessages.Reject) {
            body = outcome != null ? outcome.toString() : FhirOperationOutcome.error("invalid", "The FHIR message is invalid.");
            httpStatus = 400;
            responseStatus = Status.ERROR;
            statusMessage = "The FHIR message is invalid.";
            error = String.valueOf(connectorMap.get(FhirSerializer.ISSUES_VARIABLE));
        } else if (status == Status.ERROR) {
            String processingError = firstLine(connectorMessage.getProcessingError());
            body = FhirOperationOutcome.error("exception", processingError != null ? processingError : "The message could not be processed.");
            httpStatus = 500;
            responseStatus = Status.ERROR;
            statusMessage = "The message could not be processed.";
            error = processingError;
        } else {
            if (status == Status.FILTERED) {
                body = FhirOperationOutcome.information("The message was filtered and not processed further.");
            } else {
                body = outcome != null ? outcome.toString() : FhirOperationOutcome.information("The message was accepted.");
            }
            httpStatus = 200;
            responseStatus = Status.SENT;
            statusMessage = "FHIR OperationOutcome generated.";
        }

        boolean xml = isXml(message);
        if (xml) {
            body = FhirXml.jsonToXml(body, true);
        }
        putResponseVariable(connectorMessage, HTTP_STATUS_VARIABLE, String.valueOf(httpStatus));
        putResponseVariable(connectorMessage, CONTENT_TYPE_VARIABLE, xml ? "application/fhir+xml" : "application/fhir+json");
        return new Response(responseStatus, body, statusMessage, error);
    }

    /**
     * Puts a variable for the HTTP Listener's response settings in the channel map and the source map.
     *
     * The listener resolves ${...} from the message's merged connector message
     * (Message.getMergedConnectorMessage), which the engine builds once and caches, already when the
     * postprocessor runs, before the response is generated. The merged message has a copy of the
     * channel map, so a value put there now never reaches the listener (#33); its source map is the
     * source connector message's own map, so a value put there does. The channel map keeps it for
     * scripts and for "Auto-generate (Before processing)".
     *
     * The engine hands the source connector a read-only view of the source map
     * (Collections.unmodifiableMap of the RawMessage's map), so the value goes into the map behind
     * that view. Reaching it needs java.util opened to the plugin, which OIE's launcher does
     * (--add-opens=java.base/java.util=ALL-UNNAMED in conf/default_modules.vmoptions). Without it the
     * listener falls back to its defaults (200, or 500 for an error), as before. The smoke test
     * (.github/scripts/smoke-test.sh) checks this on every engine version the plugin declares.
     */
    static void putResponseVariable(ConnectorMessage connectorMessage, String key, String value) {
        connectorMessage.getChannelMap().put(key, value);
        Map<String, Object> sourceMap = writable(connectorMessage.getSourceMap());
        if (sourceMap != null) {
            try {
                sourceMap.put(key, value);
            } catch (UnsupportedOperationException e) {
                // read-only after all: only the channel map
            }
        }
    }

    /** The map itself, or the map behind a Collections.unmodifiableMap view; null if it cannot be written. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> writable(Map<String, Object> map) {
        if (map == null || !UNMODIFIABLE_MAP.isInstance(map)) {
            return map;
        }
        try {
            java.lang.reflect.Field backing = UNMODIFIABLE_MAP.getDeclaredField("m");
            backing.setAccessible(true);
            return writable((Map<String, Object>) backing.get(map));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // java.util not opened to the plugin (InaccessibleObjectException) or another JDK layout
            return null;
        }
    }

    private static final Class<?> UNMODIFIABLE_MAP = java.util.Collections.unmodifiableMap(new java.util.HashMap<>()).getClass();

    @Override
    public String generateResponseMessage(String message, Map<String, Object> map) throws Exception {
        String body = FhirOperationOutcome.information("The message was accepted.");
        return isXml(message) ? FhirXml.jsonToXml(body, true) : body;
    }

    private static boolean isXml(String message) {
        try {
            return FhirXml.detect(message) == FhirXml.Format.XML;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                return line.trim();
            }
        }
        return null;
    }
}
