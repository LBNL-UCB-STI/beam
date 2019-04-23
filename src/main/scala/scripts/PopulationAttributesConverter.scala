package scripts
import java.io.File

import scala.xml.{Node, NodeSeq}
import scala.xml.parsing.ConstructingParser

object PopulationAttributesConverter extends XmlFileConverter {

  override protected def fields: Seq[String] = Seq("objectId", "excludedModes", "rank")

  override def contentIterator(sourceFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document()
    val householdNodes: NodeSeq = doc.docElem \\ "objectAttributes" \ "object"

    householdNodes.toIterator.map(node => toPopulationAttribute(node).toString + LineSeparator)
  }

  private def toPopulationAttribute(node: Node): PopulationAttributes = {
    val attrs = node \\ "attribute"

    def fromSeq(name: String): String = attrs.find(_.attributes("name").text == name).get.text

    PopulationAttributes(
      objectId = node.attributes("id").toString.toInt,
      excludedModes = fromSeq("excluded-modes"),
      rank = fromSeq("rank").toInt
    )
  }

  private case class PopulationAttributes(objectId: Int, excludedModes: String, rank: Int) {
    override def toString: String = Seq(objectId, excludedModes, rank).mkString(FieldSeparator)
  }
}

//<objectAttributes>
//	<object id="1">
//		<attribute name="excluded-modes" class="java.lang.String">walk,car</attribute>
//		<attribute name="rank" class="java.lang.Integer">0</attribute>
//	</object>
//</objectAttributes>
