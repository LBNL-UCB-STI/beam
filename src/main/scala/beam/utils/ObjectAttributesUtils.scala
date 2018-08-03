package beam.utils

import java.util

import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlReader}

object ObjectAttributesUtils {

  def readObjectAttributesFromCsv(csvFilePath: String): ObjectAttributes = {
    // TODO: implement
    new ObjectAttributes
  }

  def writeObjectAttributesToCSV(objAttr: ObjectAttributes, csvFilePath: String): Unit = {
    // TODO: implement
  }

  def readObjectAttributesFromXml(xmlFilePath: String): ObjectAttributes = {
    val objectAttributes = new ObjectAttributes
    new ObjectAttributesXmlReader(objectAttributes).readFile(xmlFilePath)
    objectAttributes
  }

  def writeObjectAttributesToXML(objAttr: ObjectAttributes, xmlFilePath: String): Unit = {
    // new ObjectAttributesXmlWriter(ObjectAttributes).writeFile(xmlFilePath)

  }

  def merge(
    objectIds: util.Collection[String],
    objAttrA: ObjectAttributes,
    objAttrB: ObjectAttributes
  ): ObjectAttributes = {
    val result = new ObjectAttributes()

    /*
    for (objectId <- objectIds){
      org.matsim.utils.objectattributes.ObjectAttributesUtils.copyAllAttributes(objAttrA,result,objectId)
      org.matsim.utils.objectattributes.ObjectAttributesUtils.copyAllAttributes(objAttrB,result,objectId)
    }
     */

    result
  }

  def getAllAttributeNames(
    attributes: ObjectAttributes,
    objectId: String
  ): util.Collection[String] = {
    org.matsim.utils.objectattributes.ObjectAttributesUtils
      .getAllAttributeNames(attributes, objectId)
  }

}
