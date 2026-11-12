package com.mirth.connect.plugins.datatypes.fhir.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;

import org.junit.BeforeClass;
import org.junit.Test;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializationProperties.InvalidMessages;
import com.mirth.connect.plugins.datatypes.fhir.FhirSerializer;
import com.mirth.connect.plugins.datatypes.fhir.FhirTools;
import com.mirth.connect.plugins.datatypes.fhir.FhirValidation;

/** The OperationOutcome a sender gets back, as the engine would ask for it after the source transformer. */
public class FhirAutoResponderTest {

    private static final String VALID = "{\"resourceType\":\"Patient\",\"gender\":\"male\"}";
    private static final String INVALID = "{\"resourceType\":\"Patient\",\"gender\":\"x\",\"shoeSize\":42}";
    private static final String INVALID_XML = "<Patient xmlns=\"http://hl7.org/fhir\"><gender value=\"x\"/></Patient>";

    @BeforeClass
    public static void directEngine() {
        System.setProperty("fhir.engine.direct", "true");
    }

    private static ConnectorMessage message() {
        return new ConnectorMessage("c1", "FHIR in", 1L, 0, "s1", Calendar.getInstance(), Status.RECEIVED);
    }

    /** What the engine does first: the data type validates and fills the connector map. */
    private static ConnectorMessage processed(FhirSerializationProperties p, String raw) {
        ConnectorMessage cm = message();
        new FhirSerializer(new SerializerProperties(p, null, null)).populateMetaData(raw, cm.getConnectorMap());
        return cm;
    }

    private static void assertValidOutcome(String body) throws Exception {
        FhirValidation v = FhirTools.validate(body);
        assertTrue(v.summary() + "\n" + body, v.isValid());
    }

    @Test
    public void rejectedMessageGetsItsIssuesWith400() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        ConnectorMessage cm = processed(p, INVALID);
        Response r = new FhirAutoResponder(p).getResponse(Status.ERROR, INVALID, cm);

        assertEquals(Status.ERROR, r.getStatus());
        assertEquals("400", cm.getChannelMap().get(FhirAutoResponder.HTTP_STATUS_VARIABLE));
        assertEquals("application/fhir+json", cm.getChannelMap().get(FhirAutoResponder.CONTENT_TYPE_VARIABLE));
        String body = r.getMessage();
        assertTrue(body, body.startsWith("{\"resourceType\":\"OperationOutcome\""));
        assertTrue(body, body.contains("\"severity\":\"error\""));
        assertTrue(body, body.contains("shoeSize"));
        assertTrue(body, body.contains("\"expression\":[\"Patient.gender\"]"));
        assertTrue(body, body.contains("operationoutcome-issue-line"));
        assertValidOutcome(body);
    }

    @Test
    public void xmlInGetsXmlOut() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        ConnectorMessage cm = processed(p, INVALID_XML);
        Response r = new FhirAutoResponder(p).getResponse(Status.ERROR, INVALID_XML, cm);

        assertEquals("application/fhir+xml", cm.getChannelMap().get(FhirAutoResponder.CONTENT_TYPE_VARIABLE));
        String body = r.getMessage();
        assertTrue(body, body.startsWith("<OperationOutcome xmlns=\"http://hl7.org/fhir\">"));
        assertTrue(body, body.contains("<expression value=\"Patient.gender\"/>"));
        assertTrue(body, body.contains("<severity value=\"error\"/>\n    <code value=\"processing\"/>"));
        assertValidOutcome(body);
    }

    @Test
    public void acceptedInvalidMessageGets200WithTheIssues() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        p.setInvalidMessages(InvalidMessages.Accept);
        ConnectorMessage cm = processed(p, INVALID);
        Response r = new FhirAutoResponder(p).getResponse(Status.TRANSFORMED, INVALID, cm);

        assertEquals(Status.SENT, r.getStatus());
        assertEquals("200", cm.getChannelMap().get(FhirAutoResponder.HTTP_STATUS_VARIABLE));
        assertTrue(r.getMessage().contains("shoeSize"));
        assertValidOutcome(r.getMessage());
    }

    @Test
    public void validMessageGetsAnInformationalOutcome() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        ConnectorMessage cm = processed(p, VALID);
        Response r = new FhirAutoResponder(p).getResponse(Status.TRANSFORMED, VALID, cm);

        assertEquals("200", cm.getChannelMap().get(FhirAutoResponder.HTTP_STATUS_VARIABLE));
        assertTrue(r.getMessage(), r.getMessage().contains("\"severity\":\"information\""));
        assertValidOutcome(r.getMessage());
    }

    @Test
    public void otherErrorsGetAnExceptionWith500() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        ConnectorMessage cm = processed(p, VALID);
        cm.setProcessingError("TypeError: Cannot read property \"family\" of undefined\n\tat line 12");
        Response r = new FhirAutoResponder(p).getResponse(Status.ERROR, VALID, cm);

        assertEquals(Status.ERROR, r.getStatus());
        assertEquals("500", cm.getChannelMap().get(FhirAutoResponder.HTTP_STATUS_VARIABLE));
        assertTrue(r.getMessage(), r.getMessage().contains("\"code\":\"exception\""));
        assertTrue(r.getMessage(), r.getMessage().contains("Cannot read property \\\"family\\\" of undefined"));
        assertValidOutcome(r.getMessage());
    }

    @Test
    public void beforeProcessingValidatesItself() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        ConnectorMessage cm = message(); // nothing in the connector map yet
        Response r = new FhirAutoResponder(p).getResponse(Status.RECEIVED, INVALID, cm);

        assertEquals("400", cm.getChannelMap().get(FhirAutoResponder.HTTP_STATUS_VARIABLE));
        assertEquals(Boolean.FALSE, cm.getConnectorMap().get(FhirSerializer.VALID_VARIABLE));
        assertTrue(r.getMessage().contains("shoeSize"));
    }

    /**
     * #33: the engine builds and caches the merged connector message (for the postprocessor) before
     * it asks for the response, and the HTTP Listener resolves ${fhirHttpStatus} from that merged
     * message. Its channel map is a copy; its source map is the source message's own.
     */
    @Test
    public void theHttpListenerSeesTheStatusAndContentType() throws Exception {
        FhirSerializationProperties p = new FhirSerializationProperties();
        for (String[] c : new String[][] { { INVALID, "400", "application/fhir+json" }, { VALID, "200", "application/fhir+json" },
                { INVALID_XML, "400", "application/fhir+xml" } }) {
            ConnectorMessage source = processed(p, c[0]);
            // As the engine gives it to the source connector: a read-only view of the receiver's map.
            source.setSourceMap(java.util.Collections.unmodifiableMap(new java.util.HashMap<String, Object>()));
            Message message = new Message();
            message.setMessageId(1L);
            message.setChannelId("c1");
            message.getConnectorMessages().put(0, source);
            ConnectorMessage merged = message.getMergedConnectorMessage(); // the postprocessor
            new FhirAutoResponder(p).getResponse(Status.TRANSFORMED, c[0], source);

            assertEquals(c[1], merged.getSourceMap().get(FhirAutoResponder.HTTP_STATUS_VARIABLE));
            assertEquals(c[2], merged.getSourceMap().get(FhirAutoResponder.CONTENT_TYPE_VARIABLE));
            assertEquals("the merged message is the cached one", merged, message.getMergedConnectorMessage());
            // Scripts still find them in the channel map.
            assertEquals(c[1], source.getChannelMap().get(FhirAutoResponder.HTTP_STATUS_VARIABLE));
        }
    }
}
