package beam.utils.csv.conversion

import java.io.File

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
  }

  override def contentIterator(sourceFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document().docElem
    val peopleNodes: NodeSeq = doc \\ "population" \\ "person"
    peopleNodes.toIterator.flatMap(toPlans).map(_.toString + LineSeparator)
  }
}
