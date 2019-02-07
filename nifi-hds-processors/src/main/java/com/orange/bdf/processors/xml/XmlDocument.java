// XmlDocument.java
// (C) COPYRIGHT METASWITCH NETWORKS 2014
package com.orange.bdf.processors.xml;

import com.orange.bdf.processors.json.JsonObject;
import com.orange.bdf.processors.json.JsonValue;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class XmlDocument
{

  /**
   * The schema to parse with.
   */
  private final XmlSchema mSchema;

  /**
   * The file to parse.
   */
  private final InputSource inputSource;

  /**
   * The file to parse.
   */
  private final InputStream inputStream;

  /**
   * The parsed DOM document.
   */
  private Document mDoc;

  /**
   * Construct an XML document. Use {@link #parse()} to parse it.
   *
   * @param xiSchema
   * @param xiFile
   */
  public XmlDocument(XmlSchema xiSchema, File xiFile)
  {
    mSchema = xiSchema;
    this.inputSource = new InputSource(xiFile.toURI().toASCIIString());
    inputStream = null;
  }

  public XmlDocument(XmlSchema mSchema, InputSource inputSource) {
    this.mSchema = mSchema;
    this.inputSource = inputSource;
    inputStream = null;
  }

  public XmlDocument(XmlSchema xiSchema, InputStream inputStream)
  {
    mSchema = xiSchema;
    this.inputStream = inputStream;
    inputSource = null;
  }

  /**
   * Parse the given XML document using the given schema.
   *
   * @throws ParserConfigurationException
   * @throws SAXException
   * @throws IOException
   */
  public void parse()
      throws ParserConfigurationException, SAXException, IOException
  {
    if (mDoc != null)
    {
      return;
    }

    // Get a parser.
    DocumentBuilderFactory lFactory = mSchema.getDocumentBuilderFactory();
    DocumentBuilder lBuilder = lFactory.newDocumentBuilder();

    // Parse.
    if(inputSource != null) {
      mDoc = lBuilder.parse(inputSource);
    }
    else {
      mDoc = lBuilder.parse(inputStream);
    }

    // Check we're all hunky-dory.
    if (!mDoc.getDocumentElement().isSupported("psvi", "1.0"))
    {
      throw new RuntimeException("PSVI not supported by document");
    }
  }

  /**
   * Convert to JSON following the rules of
   * OMA-TS-REST_NetAPI_Common-V1_0-20140604-D.doc.
   *
   * @param xiMode the translation mode to use
   * @return the JSON object.
   */
  public JsonValue toJson(Translator.TranslationMode xiMode)
  {
    JsonObject lObject = JsonObject.create();
    Translator.toJson(xiMode, lObject, mDoc.getDocumentElement());
    return lObject;
  }
}
