package scripts

import java.io.File

import scala.xml.parsing.ConstructingParser
import scala.xml.{Node, NodeSeq}

/*
*** plans.csv.gz
personId,planId,planElementIndex,planElementType,activityType,x,y,endTime,mode

planElementType: ACT or LEG
mode: car, bike, walk
activityType: home, shopping

 */
object PlansXml2CsvConverter extends Xml2CsvFileConverter {

  override protected def fields: Seq[String] = Seq("personId", "planId", "planElementType", "activityIndex",
    "activityType", "locationX", "locationY", "endTime", "mode")

  private case class PlanFlat(personId: Int, planId: Int, planElementType: String, activityIndex: Int,
                              activityType: String, locationX: Double, locationY: Double, endTime: String, mode: String) {
    override def toString: String = {
      Seq(personId, planId, planElementType, activityIndex, activityType, locationX, locationY, endTime, mode).mkString(FieldSeparator)
    }
  }

  private case class Activity(activityIndex: Int, activityType: String, locationX: Double, locationY: Double, endTime: Option[String], mode: String)


  private def toActivity(node: Node, index: Int): Activity = {
    Activity(
      activityIndex = index,
      activityType = node.attributes("type").text,
      locationX = node.attributes("x").text.toDouble,
      locationY = node.attributes("x").text.toDouble,
      endTime = Option(node.attributes("end_time")).map(_.toString),
      mode = "NOT_FOUND" // TODO:could not find this field
    )
  }

  private def toPlans(personNode: Node): Seq[PlanFlat] = {
    val personId = personNode.attributes("id").text.toInt
    val planId = personId // TODO: DID NOT FIND ANY FIELD FOR THIS. using the same id as personId
    val planElementType = "NOT_FOUND" // TODO: DID NOT FOUND THIS FIELD? ACT OR LEG? WHERE?

    val activityWithIndex: Seq[(Node, Int)] = (personNode \\ "activity").zipWithIndex

    activityWithIndex.map(pair => toActivity(pair._1, pair._2 + 1))
      .map { activity: Activity =>
        PlanFlat(
          personId = personId,
          planId = planId,
          planElementType = planElementType,
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


/*
<population>
	<person id="1">
		<plan score="-494.58068848294334" selected="yes">
			<activity type="Home" x="166321.9" y="1568.87" end_time="13:45:00" />
			<activity type="Shopping" x="167138.4" y="1117" end_time="15:49:00" />
			<activity type="Home" x="166321.9" y="1568.87" end_time="18:30:21" />
			<activity type="Shopping" x="166045.2" y="2705.4" end_time="19:43:26" />
			<activity type="Home" x="166321.9" y="1568.87" />
		</plan>
	</person>
 */