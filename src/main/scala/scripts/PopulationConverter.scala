package scripts

import scala.xml.{Node, NodeSeq}
import scala.xml.parsing.ConstructingParser
import java.io.File

object PopulationConverter extends XmlFileConverter {
  private val LineSeparator: String = "\n"

  private case class Person(id: Int, planScore: Double, planSelected: Boolean, activities: Seq[Activity]) {
    override def toString: String = Seq(id, planScore, planScore, activities.mkString("[", ":", "]")).mkString(",")
  }

  private case class Activity(activityType: String, x: Double, y: Double, link: String, endTime: String) {
    override def toString: String = Seq(activityType, x, y, link, endTime).mkString("{", ",", "}")
  }

  private def toPerson(node: Node): Person = {
    val plan: Node = (node \ "plan").head
    Person(
      id = node.attributes("id").toString.toInt,
      planScore = plan.attributes("score").text.toDouble,
      planSelected = plan.attributes("selected").text.toLowerCase == "yes",
      activities = (plan \\ "activity").map(toActivity)
    )
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
  override def toCsv(sourceFile: File, destinationFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document().docElem
    val peopleNodes: NodeSeq = doc \\ "population" \ "person"
    val header = Iterator("id;planScore;planSelected;activities{type,x,y,link,endTime}", LineSeparator)
    val contentIterator = peopleNodes.toIterator.map(node => toPerson(node).toString + LineSeparator)
    header ++ contentIterator
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
