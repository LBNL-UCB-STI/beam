package scripts

import java.io.File

import scala.xml.{Node, NodeSeq}
import scala.xml.parsing.ConstructingParser

object HouseHoldAttributesConverter extends XmlFileConverter {

  protected val fields: Seq[String] = Seq("attributeId", "homeCoordX", "homeCoordY", "housingType")

  private case class HouseHoldAttributes(attributeId: Long, homeCoordX: Int, homeCoordY: Int, housingType: String) {
    override def toString: String = {
      val values = Seq(attributeId, homeCoordX, homeCoordY, housingType)
      values.mkString(FieldSeparator)
    }
  }

  private def toHouseholdAttributes(node: Node): HouseHoldAttributes = {
    val attrs = node \\ "attribute"

    def fromSeq(name: String): String = attrs.find(_.attributes("name").text == name).get.text

    HouseHoldAttributes(
      attributeId = node.attributes("id").toString.toLong,
      homeCoordX = fromSeq("homecoordx").toInt,
      homeCoordY = fromSeq("homecoordy").toInt,
      housingType = fromSeq("housingtype")
    )
  }

  override def contentIterator(sourceFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document()
    val householdNodes: NodeSeq = doc.docElem \\ "objectattributes" \ "object"
    householdNodes.toIterator.map(node => toHouseholdAttributes(node).toString + LineSeparator)
  }

}

/*
   <object id="1">
     <attribute name="homecoordx" class="java.lang.Double">166321</attribute>
     <attribute name="homecoordy" class="java.lang.Double">1568</attribute>
     <attribute name="housingtype" class="java.lang.String">House</attribute>
   </object>
 */
