package beam.router

import akka.actor.{Actor, Props}
import beam.agentsim.agents.BeamAgent.Finish
import beam.router.BeamRouter.{IterationEndsMessage, IterationStartsMessage, RoutingFailure, RoutingResponse}
import beam.router.Modes.BeamMode._
import beam.utils.csv.CsvWriter
import org.matsim.core.controler.OutputDirectoryHierarchy

/**
  * @author Dmitry Openkov
  */
class RoutingStatistic(ioController: OutputDirectoryHierarchy) extends Actor {

  private val csvHeaders =
    Seq("departure_time", "origin_x", "origin_y", "destination_x", "destination_y", "mode", "note")

  override def receive: Receive = { case IterationStartsMessage(iteration) =>
    val writer = new CsvWriter(ioController.getIterationFilename(iteration, "not_found_routes.csv.gz"), csvHeaders)
    context.become(handleRouterResponses(writer))
  }

  def handleRouterResponses(writer: CsvWriter): Receive = {
    case _: IterationEndsMessage =>
      writer.close()
      context.become(receive)
      sender() ! Finish

    case RoutingResponse(itineraries, _, Some(request), _, searchedModes, _) =>
      searchedModes.foreach { mode =>
        val tripFound =
          if (mode == RIDE_HAIL_TRANSIT) itineraries.exists(_.tripClassifier == DRIVE_TRANSIT)
          else if (mode.isTransit) itineraries.exists(_.tripClassifier.isTransit)
          else itineraries.exists(_.tripClassifier == mode)
        if (!tripFound) {
          writer.write(
            request.departureTime,
            request.originUTM.getX,
            request.originUTM.getY,
            request.destinationUTM.getX,
            request.destinationUTM.getY,
            mode,
            "trip"
          )
        }
      }
      itineraries
        .flatMap(_.legs)
        .filter(leg =>
          leg.beamLeg.travelPath.linkIds.isEmpty && leg.beamLeg.startTime > 0 && !leg.beamLeg.mode.isTransit
        )
        .foreach { leg =>
          val beamLeg = leg.beamLeg
          val start = beamLeg.travelPath.startPoint.loc
          val end = beamLeg.travelPath.endPoint.loc
          writer.write(beamLeg.startTime, start.getX, start.getY, end.getX, end.getY, beamLeg.mode, "leg")
        }

    case RoutingFailure(cause, request) =>
      writer.write(request.departureTime, request.originUTM, request.destinationUTM, "", cause.getMessage)
  }

}

object RoutingStatistic {
  def props(ioController: OutputDirectoryHierarchy): Props = Props(new RoutingStatistic(ioController))
}
