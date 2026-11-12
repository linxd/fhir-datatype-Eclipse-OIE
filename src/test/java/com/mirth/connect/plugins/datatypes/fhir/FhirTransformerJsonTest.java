package com.mirth.connect.plugins.datatypes.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

import com.mirth.connect.donkey.model.message.MessageSerializerException;
import com.mirth.connect.donkey.model.message.SerializationType;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.FhirVersion;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.OutputFormat;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.TransformerFormat;

/** Transformer Format JSON (issue #11): msg is a JavaScript object. */
public class FhirTransformerJsonTest {

    private static final String PATIENT_JSON = "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"name\":[{\"family\":\"Jansen\",\"given\":[\"Piet\"]}],\"birthDate\":\"1980-01-01\"}";

    private static final String PATIENT_XML = "<Patient xmlns=\"http://hl7.org/fhir\"><id value=\"p1\"/><name><family value=\"Jansen\"/><given value=\"Piet\"/></name><birthDate value=\"1980-01-01\"/></Patient>";

    @BeforeClass
    public static void directEngine() {
        System.setProperty("fhir.engine.direct", "true");
    }

    private static FhirSerializationProperties json() {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setTransformerFormat(TransformerFormat.JSON);
        return p;
    }

    private static FhirSerializer serializer(FhirSerializationProperties p) {
        return new FhirSerializer(new SerializerProperties(p, null, null));
    }

    /**
     * What the engine does with Transformer Format JSON: toJSON on the inbound serializer, the
     * script OIE's JavaScriptBuilder generates around the step, then fromXML on the outbound
     * serializer (the outbound serialization type stays XML, see FhirSerializer.fromXML).
     */
    private static String transform(FhirSerializer inbound, FhirSerializer outbound, String raw, String step) throws Exception {
        inbound.populateMetaData(raw, new HashMap<>());
        String transformed = inbound.toJSON(raw);
        Context cx = new ContextFactory().enterContext();
        try {
            Scriptable scope = cx.initStandardObjects();
            scope.put("transformedData", scope, transformed);
            String script = "var msg = JSON.parse(transformedData);\n" + step + "\n"
                    + "if ('xml' === typeof msg) { msg = msg.toXMLString(); } else if ('undefined' !== typeof msg && msg !== null) { msg = JSON.stringify(msg); }\n"
                    + "msg;";
            transformed = Context.toString(cx.evaluateString(scope, script, "transformer", 1, null));
        } finally {
            Context.exit();
        }
        return outbound.fromXML(transformed);
    }

    @Test
    public void theEngineAsksTheProperties() {
        assertEquals(SerializationType.XML, new FhirSerializationProperties().getSerializationType());
        assertEquals(SerializationType.JSON, json().getSerializationType());
        FhirSerializationProperties saved = json();
        saved.setTransformerFormat(null); // channels saved before 1.5.0
        assertEquals(SerializationType.XML, saved.getSerializationType());
    }

    @Test
    public void aTransformerSetsAnElementOnTheJavaScriptObject() throws Exception {
        String out = transform(serializer(json()), serializer(new FhirSerializationProperties()), PATIENT_JSON, "msg.gender = 'female';");
        assertTrue(out, out.contains("\"gender\": \"female\""));
        assertTrue(out, out.contains("\"family\": \"Jansen\""));
        // Written by the FHIR engine in the order of the specification: gender before birthDate.
        assertTrue(out, out.indexOf("\"gender\"") < out.indexOf("\"birthDate\""));
    }

    @Test
    public void readsArraysAsInFhirJson() throws Exception {
        String out = transform(serializer(json()), serializer(new FhirSerializationProperties()), PATIENT_JSON,
                "msg.name[0].given.push(msg.name[0].family.toUpperCase());");
        assertTrue(out, out.contains("\"JANSEN\""));
    }

    @Test
    public void xmlInComesToTheTransformerAsJson() throws Exception {
        FhirSerializer inbound = serializer(json());
        inbound.populateMetaData(PATIENT_XML, new HashMap<>());
        String transformed = inbound.toJSON(PATIENT_XML);
        assertTrue(transformed, transformed.contains("\"given\":[\"Piet\"]"));

        FhirSerializationProperties xmlOut = new FhirSerializationProperties();
        xmlOut.setOutputFormat(OutputFormat.XML);
        String out = transform(inbound, serializer(xmlOut), PATIENT_XML, "msg.active = true;");
        assertTrue(out, out.contains("<active value=\"true\"/>"));
    }

    @Test
    public void jsonInIsPassedOnAsItCame() throws Exception {
        FhirSerializer inbound = serializer(json());
        inbound.populateMetaData(PATIENT_JSON, new HashMap<>());
        assertEquals(PATIENT_JSON, inbound.toJSON(PATIENT_JSON));
    }

    @Test
    public void aMisspelledElementIsRefused() throws Exception {
        try {
            transform(serializer(json()), serializer(new FhirSerializationProperties()), PATIENT_JSON, "msg.gendr = 'female';");
            fail("expected the unknown element to be refused");
        } catch (MessageSerializerException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("The transformer output is not valid FHIR: "));
            assertTrue(e.getMessage(), e.getMessage().contains("gendr"));
        }
    }

    /** The step kept msg.toString(), which gives "[object Object]" for a JavaScript object. */
    @Test
    public void fhirToolsExplainsAJavaScriptObject() throws Exception {
        for (String call : new String[] { "validate", "toJson", "toXml" }) {
            try {
                FhirTools.class.getMethod(call, String.class).invoke(null, "[object Object]");
                fail(call);
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(call + ": " + e.getCause().getMessage(), e.getCause().getMessage().contains("pass JSON.stringify(msg) instead of msg or msg.toString()"));
            }
        }
    }

    @Test
    public void anInvalidMessageIsRejectedBeforeTheTransformer() {
        FhirSerializer inbound = serializer(json());
        String invalid = "{\"resourceType\":\"Patient\",\"gender\":\"x\"}";
        inbound.populateMetaData(invalid, new HashMap<>());
        try {
            inbound.toJSON(invalid);
            fail();
        } catch (MessageSerializerException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("The FHIR message is invalid"));
        }
    }

    @Test
    public void worksWithTheChannelsFhirVersion() throws Exception {
        FhirSerializationProperties in = json();
        in.setFhirVersion(FhirVersion.R5);
        FhirSerializationProperties out = new FhirSerializationProperties();
        out.setFhirVersion(FhirVersion.R5);
        String observation = "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"Glucose\"}}";
        String result = transform(serializer(in), serializer(out), observation,
                "msg.triggeredBy = [{ observation: { reference: 'Observation/o1' }, type: 'reflex' }];");
        assertTrue(result, result.contains("\"triggeredBy\""));
    }
}
