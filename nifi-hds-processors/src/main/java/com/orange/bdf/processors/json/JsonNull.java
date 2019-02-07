// JsonNull.java
// (C) COPYRIGHT METASWITCH NETWORKS 2014
package com.orange.bdf.processors.json;

public class JsonNull implements JsonValue
{
  private static JsonNull NULL = new JsonNull();

  public static JsonNull create()
  {
    return NULL;
  }

  private JsonNull()
  {
    // private constructor
  }

  @Override
  public void render(StringBuilder xiBuffer, RenderParams xiParams)
  {
    xiBuffer.append("null");
  }

  @Override
  public boolean isSimple()
  {
    return true;
  }
}
