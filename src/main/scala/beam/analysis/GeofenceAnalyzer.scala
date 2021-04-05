package beam.analysis

import beam.router.model.EmbodiedBeamLeg
import beam.sim.common.GeoUtils
import beam.sim.{BeamServices, CircularGeofence, Geofence, RideHailFleetInitializer, TAZGeofence}
import beam.utils.Statistics
import beam.utils.map.PointInfo
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import io.circe.syntax._

import scala.collection.mutable.ArrayBuffer

class GeofenceAnalyzer(beamSvc: BeamServices) extends BasicEventHandler with IterationEndsListener with LazyLogging {
  import beam.router.r5.RouteDumper._
  import beam.utils.json.AllNeededFormats._

  val errors: ArrayBuffer[PointInfo] = new ArrayBuffer[PointInfo]()

  val rideHail2Geofence: Map[String, CircularGeofence] = {
    if (beamSvc.beamConfig.beam.agentsim.agents.rideHail.initialization.initType.equalsIgnoreCase("file")) {
      RideHailFleetInitializer
        .readFleetFromCSV(beamSvc.beamConfig.beam.agentsim.agents.rideHail.initialization.filePath)
        .flatMap { fd =>
          val maybeGeofence = (fd.geofenceX, fd.geofenceY, fd.geofenceRadius) match {
            case (Some(x), Some(y), Some(r)) => Some(CircularGeofence(x, y, r))
            case _                           => None
          }
          maybeGeofence.map(g => fd.id -> g)
        }
        .toMap
    } else Map.empty[String, CircularGeofence]
  }

  logger.info(
    s"Created GeofenceAnalyzer with hashcode: ${this.hashCode()}. rideHail2Geofence size: ${rideHail2Geofence.keys.size}"
  )

  override def handleEvent(event: Event): Unit = {
    event match {
      case rre: RoutingResponseEvent =>
        val legs = rre.routingResponse.itineraries.flatMap(_.legs)
        val rideHailLegs: Seq[EmbodiedBeamLeg] = legs.collect { case leg if leg.isRideHail => leg }
        handle(rre, rideHailLegs)
      case _ =>
    }
  }

  override def reset(iteration: Int): Unit = {
    errors.clear()
  }

  def handle(routingResponseEvent: RoutingResponseEvent, rideHailLegs: Seq[EmbodiedBeamLeg]): Unit = {
    rideHailLegs.foreach { rhl =>
      rideHail2Geofence.get(rhl.beamVehicleId.toString).foreach { geofenceTmp =>
        geofenceTmp match {
          case geofence: CircularGeofence =>
            val geofenceCoord = new Coord(geofence.geofenceX, geofence.geofenceY)
            val startUtm = beamSvc.geo.wgs2Utm(rhl.beamLeg.travelPath.startPoint.loc)
            val endUtm = beamSvc.geo.wgs2Utm(rhl.beamLeg.travelPath.endPoint.loc)
            val diffStart = GeoUtils.distFormula(geofenceCoord, startUtm) - geofence.geofenceRadius
            val diffEnd = GeoUtils.distFormula(geofenceCoord, endUtm) - geofence.geofenceRadius
            if (diffStart > 0) {
              val req =
                routingResponseEvent.routingResponse.request
                  .map(r => r.asJson.toString())
                  .getOrElse("### NO REQUEST ###")
              val resp = routingResponseEvent.routingResponse.copy(request = None).asJson.toString()
              logger.info(
                s"""Geofence is broken at start point. diffStart: $diffStart.
                   |  travelPath => startUtm: $startUtm, endUtm: $endUtm
                   |  geofenceCoord => $geofenceCoord
                   |  Routing request originated by ${routingResponseEvent.routingResponse.request
                     .map(_.initiatedFrom)}: ${req}
                   |  Resp: $resp""".stripMargin
              )
              errors += PointInfo(diffStart, geofence.geofenceRadius)
            }
            if (diffEnd > 0) {
              val req =
                routingResponseEvent.routingResponse.request
                  .map(r => r.asJson.toString())
                  .getOrElse("### NO REQUEST ###")
              val resp = routingResponseEvent.routingResponse.copy(request = None).asJson.toString()
              logger.info(
                s"""Geofence is broken at end point. diffEnd: $diffEnd.
                   |  travelPath => startUtm: $startUtm, endUtm: $endUtm
                   |  geofenceCoord => $geofenceCoord
                   |  Routing request originated by ${routingResponseEvent.routingResponse.request
                     .map(_.initiatedFrom)}: ${req}
                   |  Resp: $resp""".stripMargin
              )
              errors += PointInfo(diffEnd, geofence.geofenceRadius)
            }
          case x => logger.info(s"These cases are not implemented yet: ${x.getClass}")
        }

      }
    }
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    if (errors.nonEmpty) {
      logger.info(s"Stats about violations at iteration ${event.getIteration}:")
      logger.info(s"Distance: ${Statistics(errors.map(_.offset))}")
      logger.info(s"Error(percent to the geofence radius): ${Statistics(errors.map(_.ratio * 100))}")
      errors.clear()
    } else {
      logger.info(s"There were no violation of geofence at iteration ${event.getIteration}")
    }
  }
}

object GeofenceAnalyzer {}
