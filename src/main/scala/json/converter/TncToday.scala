package json.converter

import scala.collection.JavaConverters._
import json.converter.TazOutput.TazViz
import play.api.libs.json.Json

object TncToday {

  def processJson(inputJson: String): Seq[TazViz] = {
    val res = Json.parse(inputJson)
    res.as[Seq[TazViz]]
  }

  def processJsonJava(inputJson: String): java.util.List[TazViz] = {
    processJson(inputJson).asJava
  }

}
