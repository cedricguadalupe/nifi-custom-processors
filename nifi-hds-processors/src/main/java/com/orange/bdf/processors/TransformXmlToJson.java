package com.orange.bdf.processors;

import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlContext;
import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlValidator;
import com.orange.bdf.processors.json.JsonObject;
import com.orange.bdf.processors.json.JsonString;
import com.orange.bdf.processors.json.JsonValue;
import com.orange.bdf.processors.json.RenderParams;
import com.orange.bdf.processors.wsdl.WsdlExtractor;
import com.orange.bdf.processors.xml.Translator;
import com.orange.bdf.processors.xml.XmlDocument;
import com.orange.bdf.processors.xml.XmlSchema;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.StopWatch;
import org.apache.xmlbeans.XmlError;
import org.xml.sax.SAXException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"xml", "schema", "transform", "wsdl", "json", "xsd"})
@CapabilityDescription("Validates the contents of FlowFiles against a user-specified Wsdl Soap file")
public class TransformXmlToJson extends AbstractProcessor {
    public static final PropertyDescriptor SCHEMA_FILE = new PropertyDescriptor.Builder()
            .name("Schema File")
            .description("The path to the Schema file that is to be used for validation")
            .required(true)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();

    public static final PropertyDescriptor SCHEMA_TYPE = new PropertyDescriptor.Builder()
            .name("Schema Type")
            .description("Type of schema : wsdl or xsd")
            .required(true)
            .allowableValues("xsd", "wsdl")
            .defaultValue("xsd")
            .build();

    public static final PropertyDescriptor KEEP_NAMESPACES = new PropertyDescriptor.Builder()
            .name("Keep Namespaces")
            .description("The namespaces of wsdl are kept to validate a soap request")
            .required(true)
            .allowableValues("true", "false", "deleteAll")
            .defaultValue("false")
            .build();

    public static final PropertyDescriptor CUSTOM_NAMESPACE = new PropertyDescriptor.Builder()
            .name("Custom Namespaces to keep")
            .description("The namespaces of wsdl are kept to validate a soap request useful when deleteAll is enabled")
            .required(true)
            .addValidator(org.apache.nifi.components.Validator.VALID)
            .defaultValue("")
            .build();


    public static final Relationship REL_VALID = new Relationship.Builder()
            .name("valid")
            .description("FlowFiles that are successfully validated against the schema are routed to this relationship")
            .build();

    public static final Relationship REL_EXCEPTION = new Relationship.Builder()
            .name("exception")
            .description("Exception error during the conversion xml/soap to json")
            .build();

    public static final Relationship REL_INVALID = new Relationship.Builder()
            .name("invalid")
            .description("FlowFiles that are not valid according to the specified schema are routed to this relationship")
            .build();

    public static final String WSDL_ERRORS_ATTR_NAME = "wsdl_errors";

    private List<PropertyDescriptor> properties;
    private Set<Relationship> relationships;
    private final AtomicReference<WsdlValidator> wsdlValidatorAtomicReference = new AtomicReference<>();
    private final AtomicReference<WsdlContext> contextAtomicReference = new AtomicReference<>();
    private final AtomicReference<List<Validator>> validatorsAtomicReference = new AtomicReference<>();
    private final AtomicReference<List<String>> operationsAtomicReference = new AtomicReference<>();
    private final AtomicReference<List<XmlSchema>> xmlSchemaAtomicReference = new AtomicReference<>();
    private final AtomicReference<String> customNamespacesAtomicReference = new AtomicReference<>();

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(SCHEMA_TYPE);
        properties.add(SCHEMA_FILE);
        properties.add(KEEP_NAMESPACES);
        properties.add(CUSTOM_NAMESPACE);
        this.properties = Collections.unmodifiableList(properties);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_VALID);
        relationships.add(REL_INVALID);
        relationships.add(REL_EXCEPTION);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @OnScheduled
    public void parseXsd(final ProcessContext context) {
        if("wsdl".equals(context.getProperty(SCHEMA_TYPE).getValue())) {
            final WsdlContext wsdlContext = new WsdlContext(context.getProperty(SCHEMA_FILE).getValue());
            contextAtomicReference.set(wsdlContext);
            wsdlValidatorAtomicReference.set(new WsdlValidator(wsdlContext));
            final List<String> xsdContents;
            try {
                xsdContents = WsdlExtractor.wsdlToXsd(wsdlContext, context.getProperty(KEEP_NAMESPACES).getValue());
                operationsAtomicReference.set(WsdlExtractor.extractOperations(wsdlContext));
            } catch (Exception e) {
                getLogger().error("Error during loading of wsdl");
                getLogger().error(e.getMessage());
                return;
            }
            final List<Validator> validators = new ArrayList<>();
            final List<XmlSchema> xmlSchemas = new ArrayList<>();
            for (String xsdContent : xsdContents) {
                InputStream stream = new ByteArrayInputStream(xsdContent.getBytes());
                try {
                    final Schema schema = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema").newSchema(new StreamSource(stream));
                    validators.add(schema.newValidator());
                    final XmlSchema xmlSchema = new XmlSchema(schema);
                    xmlSchemas.add(xmlSchema);
                } catch (SAXException e) {
                    if (e.getMessage() != null && !e.getMessage().contains("the prefix 'xsd' is not declared")) {
                        getLogger().warn("Error while loading wsdl", e);
                    }
                }
            }
            xmlSchemaAtomicReference.set(xmlSchemas);
            validatorsAtomicReference.set(validators);
        } else {
            final Schema schema;
            try {
                schema = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema").newSchema(new File(context.getProperty(SCHEMA_FILE).getValue()));
            } catch (SAXException e) {
                getLogger().error("Error during loading of xsd");
                getLogger().error(e.getMessage());
                return;
            }
            final List<Validator> validators = new ArrayList<>();
            final List<XmlSchema> xmlSchemas = new ArrayList<>();
            validators.add(schema.newValidator());
            final XmlSchema xmlSchema = new XmlSchema(schema);
            xmlSchemas.add(xmlSchema);
            validatorsAtomicReference.set(validators);
            xmlSchemaAtomicReference.set(xmlSchemas);
        }
        customNamespacesAtomicReference.set(context.getProperty(CUSTOM_NAMESPACE).getValue());
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        final List<FlowFile> flowFiles = session.get(50);
        if (flowFiles.isEmpty()) {
            return;
        }

        final ComponentLog logger = getLogger();

        final List<XmlError> assertionErrors = new ArrayList<>();
        final List<XmlError> assertException = new ArrayList<>();

        for (FlowFile flowFile : flowFiles) {
            final AtomicBoolean valid = new AtomicBoolean(true);
            final StopWatch stopWatch = new StopWatch(true);
            FlowFile finalFlowFile = flowFile;
            flowFile = session.write(flowFile, new StreamCallback() {
                @Override
                public void process(final InputStream in, final OutputStream outputStream) throws IOException {
                    final String xmlContent = IOUtils.toString(in);
                    try {
                        if("wsdl".equals(context.getProperty(SCHEMA_TYPE).getValue())) {
                            final WsdlValidator wsdlValidator = wsdlValidatorAtomicReference.get();
                            final List<Validator> validators = validatorsAtomicReference.get();
                            final WsdlContext wsdlContext = contextAtomicReference.get();
                            final List<XmlSchema> xmlSchemas = xmlSchemaAtomicReference.get();
                            wsdlValidator.validateXml(xmlContent, assertionErrors);
                            final List<String> operations = operationsAtomicReference.get();
                            final String customNamespaces = customNamespacesAtomicReference.get();
                            if (assertionErrors.size() == 0) {
                                wsdlContext.getSoapVersion().validateSoapEnvelope(xmlContent, assertionErrors);
                                if (assertionErrors.size() == 0) {
                                    int i = 0;
                                    for (final Validator validator : validators) {
                                        for (String operation : operations) {
                                            assertionErrors.clear();
                                            if (xmlContent.contains(operation)) {
                                                final Pattern pattern = Pattern.compile("(?s).*<[a-z|0-9]*:" + operation + "[^>]*>(.*)<\\/[a-z|0-9]*:" + operation + ">");
                                                final Matcher matcher = pattern.matcher(xmlContent);
                                                if (matcher.find()) {
                                                    final String simpleXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + System.lineSeparator() + "<" + operation + " " + customNamespaces + ">" + matcher.group(1) + "</" + operation + ">";
                                                    final InputStream stream = new ByteArrayInputStream(simpleXml.getBytes());
                                                    try {
                                                        validator.validate(new StreamSource(stream));
                                                        final XmlDocument xmlDocument = new XmlDocument(xmlSchemas.get(i), new ByteArrayInputStream(simpleXml.getBytes()));
                                                        xmlDocument.parse();
                                                        final JsonValue lJson = xmlDocument.toJson(Translator.TranslationMode.STRUCTURE_AWARE);
                                                        if(lJson instanceof JsonObject) {
                                                            ((JsonObject) lJson).put("request_id", JsonString.create(finalFlowFile.getAttribute("uuid")));
                                                            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
                                                            ((JsonObject) lJson).put("received_timestamp", JsonString.create(simpleDateFormat.format(new Date())));
                                                        }
                                                        final StringBuilder lBuffer = new StringBuilder();
                                                        final RenderParams lParams = RenderParams.createCompact();
                                                        lJson.render(lBuffer, lParams);
                                                        final String contentJson = lBuffer.toString()
                                                                .replace("\0", "")
                                                                .replace("\n", "")
                                                                .replace("\r", "");
                                                        outputStream.write(contentJson.getBytes());
                                                        assertionErrors.clear();
                                                        return;
                                                    } catch (SAXException e) {
                                                        assertionErrors.add(XmlError.forMessage("Soap request doesn't respect Wsdl (validate error = " + e.getMessage() + ")"));
                                                        valid.set(false);
                                                    }
                                                } else {
                                                    assertionErrors.add(XmlError.forMessage("Soap request doesn't respect Wsdl (no match for operation=" + operation + ")"));
                                                    valid.set(false);
                                                }
                                            } else {
                                                assertionErrors.add(XmlError.forMessage("Soap request doesn't respect Wsdl (operation=" + operation + " not found)"));
                                                valid.set(false);
                                            }
                                        }
                                        i++;
                                    }

                                }
                            }
                        } else {
                            final List<Validator> validators = validatorsAtomicReference.get();
                            final List<XmlSchema> xmlSchemas = xmlSchemaAtomicReference.get();
                            int i = 0;
                            for(Validator validator: validators) {
                                assertionErrors.clear();
                                try {
                                    validator.validate(new StreamSource(new ByteArrayInputStream(xmlContent.getBytes())));
                                    final XmlDocument xmlDocument = new XmlDocument(xmlSchemas.get(i), new ByteArrayInputStream(xmlContent.getBytes()));
                                    xmlDocument.parse();
                                    final JsonValue lJson = xmlDocument.toJson(Translator.TranslationMode.STRUCTURE_AWARE);
                                    final StringBuilder lBuffer = new StringBuilder();
                                    final RenderParams lParams = RenderParams.createCompact();
                                    lJson.render(lBuffer, lParams);
//                                    lBuffer.append("\n");
                                    final String contentJson = lBuffer.toString()
                                            .replace("\0", "")
                                            .replace("\n", "")
                                            .replace("\r", "");
                                    outputStream.write(contentJson.getBytes());
                                    assertionErrors.clear();
                                    return;
                                } catch (SAXException e) {
                                    assertionErrors.add(XmlError.forMessage("Xml is not valid"));
                                }
                                i++;
                            }
                        }
                        if(assertionErrors.size() > 0) {
                            outputStream.write(xmlContent.getBytes());
                        }
                    } catch (final Exception e) {
                        valid.set(false);
                        assertException.add(XmlError.forMessage("exception error"));
                        e.printStackTrace();
                        logger.error("Failed to validate {} against schema due to {}", new Object[]{e});
                    } finally {
                        if(assertionErrors.size() > 0 || assertException.size() > 0) {
                            outputStream.write(xmlContent.getBytes());
                            valid.set(false);
                        }
                    }
                }
            });

            if (assertionErrors.size() == 0 && assertException.size() == 0) {
                logger.debug("Successfully validated {} against schema; routing to 'valid'", new Object[]{flowFile});
                flowFile = session.putAttribute(flowFile, "mime.type", "application/json");
                session.getProvenanceReporter().modifyContent(flowFile, stopWatch.getElapsed(TimeUnit.MILLISECONDS));
                session.transfer(flowFile, REL_VALID);
            } else {
                StringBuffer sb = new StringBuffer();
                for(int i = 0; i < assertionErrors.size(); i++) {
                    if (i>0) sb.append("\n");
                    sb.append(assertionErrors.get(i).getMessage());
                }
                logger.warn("Failed to validate {} against schema; errors are : " + sb.toString() + "; routing to 'invalid'", new Object[]{flowFile});
                if(assertionErrors.size() > 0) {
                    flowFile = session.putAttribute(flowFile, WSDL_ERRORS_ATTR_NAME, sb.toString());
                    session.getProvenanceReporter().route(flowFile, REL_INVALID);
                    session.transfer(flowFile, REL_INVALID);
                } else {
//                    parseXsd(context);
                    session.getProvenanceReporter().route(flowFile, REL_EXCEPTION);
                    session.transfer(flowFile, REL_EXCEPTION);
                }
            }
        }
    }
}
