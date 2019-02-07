package com.orange.bdf.processors;

import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

/**
 * Created by mjnb4042 on 11/07/2017.
 */
public class TransformXmlToJsonTest {

    private String wsdlFilename = "src/test/resources/author.wsdl";

    @Test
    public void testValidateWsdl2() throws IOException {
        final TestRunner runner = TestRunners.newTestRunner(new TransformXmlToJson());
        runner.setProperty(TransformXmlToJson.SCHEMA_FILE, wsdlFilename);
        runner.setProperty(TransformXmlToJson.SCHEMA_TYPE, "wsdl");
        runner.setProperty(TransformXmlToJson.KEEP_NAMESPACES, "true");

        runner.enqueue(Paths.get("src/test/resources/author.xml"));
        runner.run();

        runner.assertAllFlowFilesTransferred(TransformXmlToJson.REL_INVALID, 1);
        List<MockFlowFile> mockFlowFileList = runner.getFlowFilesForRelationship(TransformXmlToJson.REL_VALID);
        for(MockFlowFile mockFlowFile: mockFlowFileList) {
            mockFlowFile.assertAttributeEquals("mime.type", "application/json");
            mockFlowFile.getId();
//            Assert.assertTrue(mockFlowFile.getSize() != 0L);
        }
    }
}
