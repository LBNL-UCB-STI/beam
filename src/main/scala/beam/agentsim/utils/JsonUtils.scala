package beam.agentsim.utils
import org.matsim.core.utils.io.IOUtils

import scala.xml.XML

/**
  * Created by sfeygin on 3/28/17.
  */
object JsonUtils {
  def processEventsFileVizData(inFile: String, outFile: String): Unit ={
    val xml = XML.loadFile(inFile)
    val events =xml\\"events"\"event"
    val out = for {event<-events if event.attribute("type").get.toString()=="pathTraversal"
    } yield event.attribute("viz_data").get.toString().replace("&quot;","\"")
    val jsonOutString = out.mkString("\n[",",\n","]\n")
    val writer = IOUtils.getBufferedWriter(outFile)
    writer.write(jsonOutString)
  }
}
