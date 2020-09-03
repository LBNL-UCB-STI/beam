package beam.analysis

import beam.agentsim.events.ModeChoiceEvent
import beam.analysis.plots.GraphAnalysis
import beam.physsim.cchRoutingAssignment.OD
import beam.router.BeamRouter.RoutingRequest
import beam.router.r5.RouteDumper.RoutingRequestEvent
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import beam.utils.csv.CsvWriter
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.io.IOUtils

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class CsvRow(personId: String, modeChoice: String, routingRequests: Vector[Int]) {
  override def toString: String = s"$personId,$modeChoice,${routingRequests.mkString(",")}"
}

class RoutingRequestAnalysis(beamConfig: BeamConfig) extends GraphAnalysis {

  private val personRoutingIds: mutable.Map[String, Vector[Int]] = mutable
    .Map[String, Vector[Int]]()
    .withDefaultValue(Vector())
  private val modeChoiceList = new ListBuffer[CsvRow]()

  override def processStats(event: Event): Unit = {
    event match {
      case RoutingRequestEvent(RoutingRequest(_, _, _, _, personId, _, _, _, requestId)) if personId.isDefined =>
        personRoutingIds(personId.get.toString) = personRoutingIds(personId.get.toString) :+ requestId
      case modeChoiceEvent: ModeChoiceEvent =>
        val personId = modeChoiceEvent.personId.toString
        val routingIds = personRoutingIds.remove(personId).getOrElse(Vector.empty)
        modeChoiceList.append(CsvRow(personId, modeChoiceEvent.mode, routingIds))
      case _ =>
    }
  }

  override def resetStats(): Unit = {
    personRoutingIds.clear()
    modeChoiceList.clear()
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val controller = event.getServices.getControlerIO
    val filePath = controller.getIterationFilename(event.getIteration, "routingModeChoice.csv")

    FileUtils.using(IOUtils.getBufferedWriter(filePath)) { bw =>
      bw.write("PersonId,ModeChoice,RoutingRequestIds")
      bw.newLine()
      modeChoiceList.foreach { modeChoice =>
        bw.write(modeChoice.toString)
        bw.newLine()
      }
    }
  }
}
