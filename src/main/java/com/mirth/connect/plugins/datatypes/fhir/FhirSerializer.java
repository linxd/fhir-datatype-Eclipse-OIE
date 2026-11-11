/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.donkey.model.message.MessageSerializer;
import com.mirth.connect.donkey.model.message.MessageSerializerException;
import com.mirth.connect.model.converters.IMessageSerializer;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.model.util.DefaultMetaData;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.InvalidMessages;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.OutputFormat;
import com.mirth.connect.util.ErrorMessageBuilder;

/**
 * FHIR JSON or XML in, FHIR XML for the transformer, FHIR JSON or XML out.
 *
 * Validation happens in {@link #populateMetaData}, which the engine calls first for every message,
 * whether or not the connector has a filter or transformer. The result is kept for the thread, and
 * {@link #toXML} or {@link #transformWithoutSerializing} rejects the message when it is invalid and
 * the channel says so; otherwise the connector map gets the issues.
 */
public class FhirSerializer implements IMessageSerializer {

    /** Connector map variables set by inbound validation. */
    public static final String VALID_VARIABLE = "fhirValid";
    public static final String ISSUE_COUNT_VARIABLE = "fhirIssueCount";
    public static final String ISSUES_VARIABLE = "fhirIssues";
    /** The issues as a FHIR OperationOutcome resource (JSON), ready to return to the sender. */
    public static final String OPERATION_OUTCOME_VARIABLE = "fhirOperationOutcome";

    static final String TRANSFORMER_OUTPUT_INVALID = "The transformer output is not valid FHIR";

    private static final Logger logger = LogManager.getLogger(FhirSerializer.class);

    /** The last message validated on this thread, so each message is validated once. */
    private static final ThreadLocal<Object[]> LAST_VALIDATION = new ThreadLocal<>();

    private final FhirSerializationProperties properties;

    public FhirSerializer(SerializerProperties serializerProperties) {
        FhirSerializationProperties p = null;
        if (serializerProperties != null) {
            p = (FhirSerializationProperties) serializerProperties.getSerializationProperties();
        }
        properties = p != null ? p : new FhirSerializationProperties();
        if ((properties.isValidateInbound() || properties.isValidateOutbound()) && FhirEngineLoader.isServer()) {
            warmUp();
        }
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public boolean isSerializationRequired(boolean toXml) {
        return toXml ? properties.isValidateInbound() : properties.isValidateOutbound();
    }

    @Override
    public void populateMetaData(String message, Map<String, Object> map) {
        String type = FhirXml.resourceType(message);
        if (type != null) {
            map.put(DefaultMetaData.TYPE_VARIABLE_MAPPING, type);
        }
        map.put(DefaultMetaData.VERSION_VARIABLE_MAPPING, properties.getFhirVersion().name());
        if (!properties.isValidateInbound()) {
            return;
        }
        try {
            FhirValidation validation = inboundValidation(message);
            if (validation != null) {
                map.put(VALID_VARIABLE, validation.isValid());
                map.put(ISSUE_COUNT_VARIABLE, validation.getProblems().size());
                map.put(ISSUES_VARIABLE, validation.summary());
                map.put(OPERATION_OUTCOME_VARIABLE, FhirOperationOutcome.fromValidation(validation));
            }
        } catch (Exception e) {
            // toXML reports it on the message; here it would only be lost.
            logger.debug("FHIR validation failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getMetaDataFromMessage(String message) {
        Map<String, Object> map = new HashMap<>();
        populateMetaData(message, map);
        return map;
    }

    @Override
    public String toXML(String source) throws MessageSerializerException {
        refuseDoctype(source);
        checkInbound(source);
        try {
            return FhirXml.toTransformerXml(source, properties.isStripNamespaces());
        } catch (Exception e) {
            throw error("Error converting FHIR to XML", e);
        }
    }

    @Override
    public String transformWithoutSerializing(String message, MessageSerializer outboundSerializer) throws MessageSerializerException {
        refuseDoctype(message);
        checkInbound(message);
        if (outboundSerializer instanceof FhirSerializer) {
            return ((FhirSerializer) outboundSerializer).encode(message, false);
        }
        return null;
    }

    // ------------------------------------------------------------------ outbound

    @Override
    public String fromXML(String source) throws MessageSerializerException {
        refuseDoctype(source);
        if (FhirXml.detect(source) == FhirXml.Format.JSON) {
            // Transformer Format JSON: the engine asks the outbound data type for XML all the same, because
            // it takes the outbound serialization type from deserialization properties, which FHIR has not.
            return fromJSON(source);
        }
        return encode(FhirXml.addNamespace(source.trim()), true);
    }

    /**
     * A FHIR message (JSON, or XML with namespace) in the output format, validated when asked.
     * XML that comes out of a transformer is written again by the FHIR engine, so its elements are
     * in the order of the specification; a message that did not pass a transformer is left as is.
     */
    String encode(String message, boolean fromTransformer) throws MessageSerializerException {
        if (properties.isValidateOutbound()) {
            // Before converting: the conversion refuses what it cannot represent, with a less useful message.
            FhirValidation validation = validate(message);
            if (validation != null && !validation.isValid()) {
                throw invalid("The outbound FHIR message is invalid", validation);
            }
        }
        String output;
        try {
            FhirXml.Format in = FhirXml.detect(message);
            OutputFormat out = properties.getOutputFormat();
            String version = properties.getFhirVersion().name();
            if (in == FhirXml.Format.XML && out == OutputFormat.JSON) {
                output = engine("convert FHIR XML to JSON").toJson(message, version, properties.isPrettyPrint());
            } else if (in == FhirXml.Format.JSON && out == OutputFormat.XML) {
                output = engine("convert FHIR JSON to XML").toXml(message, version, properties.isPrettyPrint());
            } else if (in == FhirXml.Format.XML && fromTransformer && FhirEngineLoader.isServer()) {
                // The transformer may have added elements anywhere; FHIR XML needs the order of the specification.
                output = engine("write FHIR XML").reorderXml(message, version, properties.isPrettyPrint());
            } else if (in == FhirXml.Format.JSON && fromTransformer && FhirEngineLoader.isServer()) {
                // JSON.stringify: one line, and a misspelled element would otherwise go out unnoticed.
                output = engine("write FHIR JSON").reorderJson(message, version, properties.isPrettyPrint());
            } else {
                output = message;
            }
        } catch (MessageSerializerException e) {
            throw e;
        } catch (Exception e) {
            // After a transformer the cause is nearly always the script, for example a misspelled element.
            throw error(fromTransformer ? TRANSFORMER_OUTPUT_INVALID : "Error converting FHIR to " + properties.getOutputFormat(), e);
        }
        return output;
    }

    // ------------------------------------------------------------------ validation

    /** FHIR XML with a DTD is refused before anything parses it (see FhirXml.hasDoctype). */
    private static void refuseDoctype(String message) throws MessageSerializerException {
        if (FhirXml.hasDoctype(message)) {
            String text = "The FHIR message was refused: " + FhirXml.DOCTYPE_REFUSED;
            throw new MessageSerializerException(text, null, text);
        }
    }

    private void checkInbound(String message) throws MessageSerializerException {
        if (!properties.isValidateInbound()) {
            return;
        }
        FhirValidation validation;
        try {
            validation = inboundValidation(message);
        } catch (Exception e) {
            throw error("FHIR validation could not run", e);
        }
        if (validation != null && !validation.isValid() && properties.getInvalidMessages() == InvalidMessages.Reject) {
            throw invalid("The FHIR message is invalid", validation);
        }
    }

    private FhirValidation inboundValidation(String message) throws Exception {
        Object[] last = LAST_VALIDATION.get();
        if (last != null && last[0] == properties && message.equals(last[1])) {
            return (FhirValidation) last[2];
        }
        FhirValidation validation = validate(message);
        LAST_VALIDATION.set(new Object[] { properties, message, validation });
        return validation;
    }

    /** Null in the Administrator, where the engine is not available (and not needed). */
    private FhirValidation validate(String message) throws MessageSerializerException {
        if (!FhirEngineLoader.isServer()) {
            return null;
        }
        try {
            FhirXml.detect(message);
            FhirEngine engine = engine("validate FHIR");
            return new FhirValidation(engine.validate(message, properties.engineOptions()), properties.getFailOn());
        } catch (MessageSerializerException e) {
            throw e;
        } catch (Exception e) {
            throw error("FHIR validation could not run", e);
        }
    }

    private FhirEngine engine(String purpose) throws MessageSerializerException {
        try {
            return FhirEngineLoader.engine();
        } catch (IllegalStateException e) {
            throw error("Cannot " + purpose, e);
        }
    }

    private void warmUp() {
        Map<String, String> options = properties.engineOptions();
        Thread thread = new Thread(() -> {
            try {
                FhirEngineLoader.engine().warmUp(options);
            } catch (Exception e) {
                logger.warn("FHIR data type: " + e.getMessage());
            }
        }, "FHIR validator warm-up");
        thread.setDaemon(true);
        thread.start();
    }

    private MessageSerializerException invalid(String what, FhirValidation validation) {
        String text = what + " (" + validation.getProblems().size() + " issue(s)):\n" + validation.summary();
        return new MessageSerializerException(text, null, text);
    }

    private MessageSerializerException error(String what, Exception e) {
        return new MessageSerializerException(what + ": " + e.getMessage(), e, ErrorMessageBuilder.buildErrorMessage(getClass().getSimpleName(), what, e));
    }

    @Override
    public String toJSON(String source) throws MessageSerializerException {
        refuseDoctype(source);
        checkInbound(source);
        try {
            if (FhirXml.detect(source) == FhirXml.Format.JSON) {
                return source; // as it came in, like the XML path
            }
            if (!FhirEngineLoader.isServer()) {
                // Only the FHIR engine knows which elements are lists; it is not available in the Administrator.
                throw new IllegalArgumentException("With Transformer Format JSON, use a FHIR JSON message as template.");
            }
            return engine("convert FHIR XML to JSON").toJson(FhirXml.addNamespace(source.trim()), properties.getFhirVersion().name(), false);
        } catch (MessageSerializerException e) {
            throw e;
        } catch (Exception e) {
            throw error("Error converting FHIR to JSON", e);
        }
    }

    @Override
    public String fromJSON(String source) throws MessageSerializerException {
        return encode(source.trim(), true);
    }
}
