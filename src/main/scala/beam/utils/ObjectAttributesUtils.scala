package beam.utils

import beam.agentsim.infrastructure.TAZCreatorSctript.tazinfrastructureAttributesFilePath
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlReader, ObjectAttributesXmlWriter}


object ObjectAttributesUtils {

  def readObjectAttributesFromCsv(csvFilePath: String):ObjectAttributes = {
    // TODO: implement
    new ObjectAttributes
  }

  def writeObjectAttributesToCSV(objAttr:ObjectAttributes,csvFilePath: String)={
    // TODO: implement
  }

  def readObjectAttributesFromXml(xmlFilePath: String):ObjectAttributes = {
    val objectAttributes=new ObjectAttributes
    new ObjectAttributesXmlReader(objectAttributes).readFile(xmlFilePath)
    objectAttributes
  }

  def writeObjectAttributesToXML(objAttr:ObjectAttributes,xmlFilePath: String)={
    new ObjectAttributesXmlWriter(ObjectAttributes).writeFile(xmlFilePath)
  }


}
