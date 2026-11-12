/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mirth.connect.donkey.util.DonkeyElement;
import com.mirth.connect.donkey.util.purge.PurgeUtil;
import com.mirth.connect.model.datatype.BatchProperties;
import com.mirth.connect.model.datatype.DataTypePropertyDescriptor;
import com.mirth.connect.model.datatype.PropertyEditorType;

/** How a batch is split when Process Batch is enabled on the source connector. */
public class FhirBatchProperties extends BatchProperties {

    public enum SplitType {
        Bundle_Entry, JavaScript;

        @Override
        public String toString() {
            return super.toString().replace('_', ' ');
        }
    }

    private SplitType splitType = SplitType.Bundle_Entry;
    private String batchScript = "";

    @Override
    public Map<String, DataTypePropertyDescriptor> getPropertyDescriptors() {
        Map<String, DataTypePropertyDescriptor> properties = new LinkedHashMap<String, DataTypePropertyDescriptor>();

        properties.put("splitType", new DataTypePropertyDescriptor(getSplitType(), "Split Batch By", "Select the method for splitting the batch message.  This option has no effect unless Process Batch is enabled in the connector.\n\nBundle Entry: every Bundle.entry.resource becomes a separate message, in the format it came in (JSON or XML). The source map gets fhirBundleType, fhirBundleId, fhirEntryIndex (0 for the first entry), fhirEntryCount, fhirEntryFullUrl, fhirEntryRequestMethod and fhirEntryRequestUrl. Entries without a resource are skipped. A message that is not a Bundle stays one message.\n\nJavaScript: Use JavaScript to split messages.", PropertyEditorType.OPTION, SplitType.values()));
        properties.put("batchScript", new DataTypePropertyDescriptor(getBatchScript(), "JavaScript", "Enter JavaScript that splits the batch, and returns the next message.  This script has access to 'reader', a Java BufferedReader, to read the incoming data stream.  The script must return a string containing the next message, or a null/empty string to indicate end of input.  This option has no effect unless Process Batch is enabled in the connector.", PropertyEditorType.JAVASCRIPT));

        return properties;
    }

    @Override
    public void setProperties(Map<String, Object> properties) {
        if (properties != null) {
            if (properties.get("splitType") != null) {
                splitType = FhirSerializationProperties.toEnum(SplitType.class, properties.get("splitType"));
            }

            if (properties.get("batchScript") != null) {
                batchScript = properties.get("batchScript").toString();
            }
        }
    }

    public SplitType getSplitType() {
        return splitType != null ? splitType : SplitType.Bundle_Entry;
    }

    public void setSplitType(SplitType splitType) {
        this.splitType = splitType;
    }

    public String getBatchScript() {
        return batchScript != null ? batchScript : "";
    }

    public void setBatchScript(String batchScript) {
        this.batchScript = batchScript;
    }

    // @formatter:off
    @Override public void migrate3_0_1(DonkeyElement element) {}
    @Override public void migrate3_0_2(DonkeyElement element) {}
    @Override public void migrate3_1_0(DonkeyElement element) {}
    @Override public void migrate3_2_0(DonkeyElement element) {}
    @Override public void migrate3_3_0(DonkeyElement element) {}
    @Override public void migrate3_4_0(DonkeyElement element) {}
    @Override public void migrate3_5_0(DonkeyElement element) {}
    @Override public void migrate3_6_0(DonkeyElement element) {}
    @Override public void migrate3_7_0(DonkeyElement element) {}
    @Override public void migrate3_9_0(DonkeyElement element) {}
    @Override public void migrate3_11_0(DonkeyElement element) {}
    @Override public void migrate3_11_1(DonkeyElement element) {}
    @Override public void migrate3_12_0(DonkeyElement element) {}
    // @formatter:on

    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purgedProperties = new HashMap<String, Object>();
        purgedProperties.put("splitType", getSplitType());
        purgedProperties.put("batchScriptLines", PurgeUtil.countLines(getBatchScript()));
        return purgedProperties;
    }
}
