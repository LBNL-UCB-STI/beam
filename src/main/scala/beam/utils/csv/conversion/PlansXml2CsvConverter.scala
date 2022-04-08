package beam.utils.csv.conversion

import java.io.File
import scala.collection.JavaConverters._
import scala.xml.parsing.ConstructingParser
import scala.xml.{Node, NodeSeq}

object PlansXml2CsvConverter extends Xml2CsvFileConverter {

  override protected def fields: Seq[String] =
    Seq(
      "personId",
      "planId",
      "planElementType",
      "activityIndex",
      "activityType",
      "locationX",
      "locationY",
      "endTime",
      "mode"
    )

  private case class PlanFlat(
    personId: String,
    planId: Int,
    planElementType: String,
    activityIndex: Int,
    activityType: String,
    locationX: Double,
    locationY: Double,
    endTime: String,
    mode: String
  ) {

    override def toString: String = {
      Seq(personId, planId, planElementType, activityIndex, activityType, locationX, locationY, endTime, mode).mkString(
        FieldSeparator
      )
    }
  }

  private case class ActivityOrLeg(
    activityIndex: Int,
    activityType: String,
    locationX: Double,
    locationY: Double,
    endTime: Option[String],
    mode: String,
    elementType: String
  )

  private def toActivity(node: Node, index: Int): ActivityOrLeg = {
    ActivityOrLeg(
      activityIndex = index,
      activityType = Option(node.attributes("type")).map(_.toString.trim).getOrElse(""),
      locationX = Option(node.attributes("x")).map(_.text.toDouble).getOrElse(0),
      locationY = Option(node.attributes("y")).map(_.text.toDouble).getOrElse(0),
      endTime = Option(node.attributes("end_time")).map(_.toString),
      mode = Option(node.attributes("mode")).map(_.toString).getOrElse(""),
      node.label
    )
  }

  private def toPlans(personNode: Node): Seq[PlanFlat] = {
//    val unavailablePerson: Seq[String] = Seq("3747042.0",
//      "7390632",
//      "2260240")
    //    ,
    //    3802832,
    //    3497544,
    //    5295028,
    //    1147653,
    //    46727,
    //    7097667,
    //    1147655,
    //    2119811,
    //    1147652,
    //    1381305,
    //    4242437,
    //    4479503,
    //    583909,
    //    3237031,
    //    3802833,
    //    3315816,
    //    46726,
    //    1225597,
    //    5409219,
    //    46729,
    //    5854546,
    //    5409214,
    //    6352267,
    //    2583224,
    //    4401601,
    //    2337872,
    //    6352266,
    //    5854548,
    //    2337876,
    //    4161544,
    //    2583225,
    //    3591276,
    //    2769219,
    //    1765765,
    //    6197131,
    //    5409218,
    //    6087332,
    //    3537879,
    //    5374434,
    //    2960697,
    //    1363260,
    //    4414019,
    //    4888615,
    //    1453988,
    //    5295029,
    //    1399597,
    //    5854545,
    //    2063349,
    //    1813545,
    //    6188107,
    //    189434,
    //    2119812,
    //    2337871,
    //    1720442,
    //    1453986,
    //    6818294,
    //    238520,
    //    5409215,
    //    2677860,
    //    1108099,
    //    7390633,
    //    2008723,
    //    341029,
    //    2337874,
    //    2188979,
    //    5559628,
    //    1673843,
    //    2483459,
    //    4015668,
    //    5559627,
    //    873102,
    //    3595313,
    //    46728,
    //    238522,
    //    3802831,
    //    6818292,
    //    7390631,
    //    7185154,
    //    2859345,
    //    6437314,
    //    4401600,
    //    1453985,
    //    300876,
    //    6097636,
    //    671038,
    //    5559626,
    //    6335557,
    //    1733759)
    val personId = personNode.attributes("id").text
    val planId = 1 // currently only one plan is supported

    val seq = personNode \ "plan"
    val childWithIndex: Seq[(Node, Int)] = seq.headOption
      .map { node =>
        node.child
          .filter(elem => elem.head.label == "activity" || elem.head.label == "leg")
          .zipWithIndex
      }
      .getOrElse(Seq.empty)

    childWithIndex
      .map(pair => toActivity(pair._1, pair._2 + 1))
      .map { activity: ActivityOrLeg =>
        PlanFlat(
          personId = personId,
          planId = planId,
          planElementType = activity.elementType,
          activityIndex = activity.activityIndex,
          activityType = activity.activityType,
          locationX = activity.locationX,
          locationY = activity.locationY,
          endTime = activity.endTime.getOrElse(""),
          mode = activity.mode
        )
      }
  }.asJava

  override def contentIterator(sourceFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document().docElem
    val peopleNodes: NodeSeq = doc \\ "population" \\ "person"
    peopleNodes.toIterator.flatMap(toPlans).map(_.toString + LineSeparator)
  }
}
