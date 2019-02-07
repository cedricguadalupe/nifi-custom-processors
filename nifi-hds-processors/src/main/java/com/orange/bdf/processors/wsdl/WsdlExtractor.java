package com.orange.bdf.processors.wsdl;

import com.eviware.soapui.impl.wsdl.support.wsdl.WsdlContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;

import javax.wsdl.Operation;
import javax.wsdl.PortType;
import javax.wsdl.extensions.schema.Schema;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.util.*;

/**
 * Created by mjnb4042 on 26/04/2017.
 */
public class WsdlExtractor {

    public static List<String> extractOperations(WsdlContext wsdlContext) throws Exception {
        ArrayList<String> operations = new ArrayList<>();
        for(PortType portType: (Collection<PortType>) wsdlContext.getDefinition().getPortTypes().values()) {
            for(Operation operation: (List<Operation>) portType.getOperations()) {
                operations.add(operation.getName());
            }
        }
        return operations;
    }



    public static List<String> wsdlToXsd(WsdlContext wsdlContext, String keepNamespace) throws Exception {
        ArrayList<String> xsdContents = new ArrayList<>();
        if(wsdlContext == null) {
            return xsdContents;
        }
        if(wsdlContext.getDefinition() == null) {
            return xsdContents;
        }
        if(wsdlContext.getDefinition().getTypes() == null) {
            return xsdContents;
        }
        for(Schema schema: (List<Schema>) wsdlContext.getDefinition().getTypes().getExtensibilityElements()) {
            Document xsdDoc = getDocBuilder().newDocument();
            Element element = schema.getElement();
            DOMImplementationLS domImplLS = (DOMImplementationLS) xsdDoc
                    .getImplementation();
            LSSerializer serializer = domImplLS.createLSSerializer();


            boolean removeTargetNamespace = true;
            String targetNamespace = "";
            if (element.hasAttribute("targetNamespace")) {
                targetNamespace = element.getAttribute("targetNamespace");
            }

            List<String> namespaces = new ArrayList<>();
            for (int i = 0; i < element.getAttributes().getLength(); i++) {
                if (element.getAttributes().item(i).getNodeValue().equals(targetNamespace)) {
                    if (!element.getAttributes().item(i).getNodeName().equals("targetNamespace")) {
                        removeTargetNamespace = false;
                        if(!element.getAttributes().item(i).getNodeName().equals("xmlns:xsd")) {
                            namespaces.add(element.getAttributes().item(i).getNodeName());
                        }
                    }
                }
            }
            if("true".equals(keepNamespace)) {
                for(Map.Entry<String, String> entry: (Set<Map.Entry<String, String>>)wsdlContext.getDefinition().getNamespaces().entrySet()) {
                    element.setAttribute("xmlns:" + entry.getKey(), entry.getValue());
                }
            }

            if (removeTargetNamespace && "false".equals(keepNamespace)) {
                element.removeAttribute("targetNamespace");
            }

            if("deleteAll".equals(keepNamespace)) {
                element.removeAttribute("targetNamespace");
                for(String namespace: namespaces) {
                    element.removeAttribute(namespace);
                }
            }

            String xsdContent = serializer.writeToString(element);

            xsdContents.add(xsdContent.replace("?>", "?>" + System.lineSeparator()).replace("UTF-16", "UTF-8"));
        }
        return xsdContents;
    }

    private static DocumentBuilder getDocBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder;
    }
}
