package beam.analysis

import beam.agentsim.events.PathTraversalEvent
import beam.analysis.TransitOccupancyByStopAnalysis.fileName
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable.ListBuffer

class TransitOccupancyByStopAnalysis extends BasicEventHandler with IterationEndsListener with LazyLogging {

  val pathTraversalEvents = new ListBuffer[PathTraversalEvent]

  override def handleEvent(event: Event): Unit = {
    event match {
      case pte: PathTraversalEvent if pte.mode.isTransit => pathTraversalEvents.append(pte)
      case _                                             =>
    }
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    if (pathTraversalEvents.nonEmpty) {
      val filePath =
        event.getServices.getControlerIO.getIterationFilename(event.getServices.getIterationNumber, fileName)
      writeCSVRows(filePath)
      pathTraversalEvents.clear()
    }
  }

  def writeCSVRows(path: String): Unit = {

    val analysis: Map[String, List[String]] =
      pathTraversalEvents.toList.groupBy(_.vehicleId.toString).map { case (k, v) =>
        k -> v.map(_.numberOfPassengers.toString)
      }

    val max = if (analysis.nonEmpty) {
      analysis.values.map(_.size).max
    } else {
      logger.warn("No Transit PathTraversal event was captured. Either transit is deactivated or something is broken")
      0
    }
    val resultData = analysis.map { case (k, v) => k -> getList(max, v) }
    val values = resultData.values.transpose
    val headers = analysis.keys.toIndexedSeq

    val csvWriter = new CsvWriter(path, headers)
    values.foreach(v => csvWriter.writeRow(v.toIndexedSeq))

    csvWriter.close()

  }

  def getList(maxSize: Int, list: List[String]): List[String] = {
    list ::: List.fill(maxSize - list.size)("")
  }
}

object TransitOccupancyByStopAnalysis {
  val fileName = "transitOccupancyByStop.csv"

  def transitOccupancySkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("TransitOccupancyByStopAnalysis", s"$fileName", iterationLevel = true)(
      """
         Transit vehicle id | Header of the file contains all the transit vehicle ids
         Vehicle occupancy  | Each column contains number of passengers in the vehicle at corresponding stop index
        """
    )
}
