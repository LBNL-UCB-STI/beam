package scripts

import com.typesafe.scalalogging.StrictLogging

import scala.collection.mutable
import scala.io.BufferedSource

object CompareLogs extends App with StrictLogging {

  val PersonIdPattern = "(@@@\\[Some\\()(.+?)(\\)\\])(.*)".r("a")

  val map = mutable.Map.empty[String, PersonLineLog]
  case class PersonLineLog(personId: String, xmlContent: Seq[String], csvContent: Seq[String])
  case class PersonContent(personId: String, content: String)

  def loadFile(path: String): Seq[String] = {
    val source: BufferedSource = scala.io.Source.fromFile(path)
    try {
      source
        .getLines()
        .filter(_.startsWith("@@@[Some("))
        .toList
    } finally {
      source.close()
    }
  }

  def extractPersonContent(str: String): Option[PersonContent] = {
    str match {
      case PersonIdPattern(_, id, _, content) => Some(PersonContent(id, content))
      case _                                  => None
    }
  }

  def compare(): Unit = {
    val xml = loadFile("/src/beam/output/sf-light/sf-light-1k-xml__2019-06-22_12-49-33/beamLog.out")
      .flatMap(extractPersonContent)
      .groupBy(_.personId)

    val csv: Map[String, Seq[PersonContent]] =
      loadFile("/src/beam/output/sf-light/sf-light-1k-csv__2019-06-22_13-24-20/beamLog.out")
        .flatMap(extractPersonContent)
        .groupBy(_.personId)

    xml.foreach { case (id: String, lines: Seq[PersonContent]) =>
      val obj: PersonLineLog = map.getOrElseUpdate(id, PersonLineLog(id, Seq.empty, Seq.empty))
      map.update(id, obj.copy(xmlContent = obj.xmlContent ++ lines.map(_.content)))
    }

    csv.foreach { case (id: String, lines: Seq[PersonContent]) =>
      val obj = map.getOrElseUpdate(id, PersonLineLog(id, Seq.empty, Seq.empty))
      map.update(id, obj.copy(csvContent = obj.csvContent ++ lines.map(_.content)))
    }

    map.toList
      .sortBy(_._1)
      .foreach { case (id: String, log: PersonLineLog) =>
        val xml = log.xmlContent.zip(log.csvContent).filterNot(v => v._1 == v._2)
        val csv = log.csvContent.zip(log.xmlContent).filterNot(v => v._1 == v._2)
        val full = if (xml.size > csv.size) xml else csv
        val outputDiff: Seq[String] = full.map { case (a, b) =>
          s"[$id] Xml:$a\n[$id] Csv:$b"
        }
        logger.warn(outputDiff.mkString("\n", "\n", "\n"))
      }
  }

  compare()
}
