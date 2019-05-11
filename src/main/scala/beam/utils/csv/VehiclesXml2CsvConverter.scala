package beam.utils.csv

import java.io.File

import scala.xml.parsing.ConstructingParser
import scala.xml.{Node, NodeSeq}

object VehiclesXml2CsvConverter extends Xml2CsvFileConverter {
  override protected def fields: Seq[String] = Seq("vehicleId", "vehicleType", "householdId")

  private case class Activity(activityType: String, x: Double, y: Double, link: String, endTime: String) {
    override def toString: String = Seq(activityType, x, y, link, endTime).mkString(FieldSeparator)
  }

  private case class Vehicle(vehicleId: String, vehicleType: String, householdId: Int) {
    override def toString: String = Seq(vehicleId, vehicleType, householdId).mkString(FieldSeparator)
  }

  private def toActivity(node: Node): Activity = {
    Activity(
      activityType = node.attributes("type").text,
      x = node.attributes("x").text.toDouble,
      y = node.attributes("y").text.toDouble,
      link = node.attribute("link").map(_.text).getOrElse(""),
      endTime = node.attribute("end_time").map(_.text).getOrElse("")
    )
  }

//  private def readHouseHoldIdToAttributes(): HouseHoldIdToAttributes = {
//    def toHouseholdAttributes(node: Node): HouseHoldAttributes = {
//      val attrs = node \\ "attribute"
//
//      def fromSeq(name: String): String = attrs.find(_.attributes("name").text == name).get.text
//
//      HouseHoldAttributes(
//        householdId = node.attributes("id").toString.toInt,
//        homeCoordX = fromSeq("homecoordx").toInt,
//        homeCoordY = fromSeq("homecoordy").toInt,
//        housingType = fromSeq("housingtype")
//      )
//    }
//
//    val parser = ConstructingParser.fromFile(householdAttributesXml, preserveWS = true)
//    val doc = parser.document()
//    val householdNodes: NodeSeq = doc.docElem \\ "objectattributes" \ "object"
//    val r = householdNodes.toIterator.map(node => toHouseholdAttributes(node))
//      .map(hha => (hha.householdId, hha))
//      .toMap
//    r
//  }

//  private def toVehicle(node: Node): Vehicle = {
//    val attrs = node \\ "attribute"
//
//    def fromSeq(name: String): String = attrs.find(_.attributes("name").text == name).get.text
//
//    PersonAttributes(
//      objectId = node.attributes("id").toString.toInt,
//      excludedModes = fromSeq("excluded-modes"),
//      rank = fromSeq("rank").toInt
//    )
//  }

  override def contentIterator(sourceFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document().docElem
    val peopleNodes: NodeSeq = doc \ "household"

    //    val memberToHousehold = readMemberToHousehold()
    //    val memberToRank: MemberToRank = readMember2Rank()

//    peopleNodes.toIterator.map(node => toPerson(node, memberToHousehold, memberToRank).toString + LineSeparator)
    Iterator()
  }
}
