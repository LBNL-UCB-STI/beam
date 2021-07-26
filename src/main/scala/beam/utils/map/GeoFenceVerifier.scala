package beam.utils.map

import java.io.Closeable

import beam.agentsim.events.PathTraversalEvent
import beam.sim.common.GeoUtils
import beam.sim.{CircularGeofence, Geofence, RideHailFleetInitializer}
import beam.utils.{EventReader, ProfilingUtils, Statistics}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event

import scala.util.Try

case class PointInfo(offset: Double, geofenceRadius: Double) {
  val ratio: Double = if (geofenceRadius.equals(0d)) Double.NaN else offset / geofenceRadius
}

object GeoFenceVerifier extends LazyLogging {

  val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  def isRideHailPTE(event: Event): Boolean = {
    val attribs = event.getAttributes
    // We need only PathTraversal with mode `CAR` and ride hail
    val isNeededEvent = event.getEventType == "PathTraversal" && Option(attribs.get("mode")).contains("car") &&
      Option(attribs.get("vehicle")).exists(vehicle => vehicle.contains("rideHailVehicle-"))
    isNeededEvent
  }

  def main(args: Array[String]): Unit = {
    assert(
      args.length == 2,
      s"Please provide first arg as a path to events and second arg as a path to ride hail fleet data. Currently provided ${args.length} args"
    )
    val pathToEvents = args(0)
    val pathToRideHailFleetData = args(1)

    val fleetData = RideHailFleetInitializer.readFleetFromCSV(pathToRideHailFleetData)
    val vehIdToGeofence = fleetData.map { rhaInput =>
      val maybeGeofence = (rhaInput.geofenceX, rhaInput.geofenceY, rhaInput.geofenceRadius) match {
        case (Some(x), Some(y), Some(r)) => Some(CircularGeofence(x, y, r))
        case _                           => None
      }
      rhaInput.id -> maybeGeofence
    }.toMap

    // This is lazy, it just creates an iterator
    val (events: Iterator[Event], closable: Closeable) =
      EventReader.fromCsvFile(pathToEvents, isRideHailPTE)

    try {
      // Actual reading happens here because we force computation by `toArray`
      val pathTraversalEvents = ProfilingUtils.timed("Read PathTraversal and filter by mode", x => logger.info(x)) {
        events.map(PathTraversalEvent.apply).toArray
      }
      logger.info(s"pathTraversalEvents size: ${pathTraversalEvents.length}")
      val geofenceErrorPerPte = pathTraversalEvents.map { pte =>
        vehIdToGeofence.get(pte.vehicleId.toString).map { maybeGeofence =>
          val error = maybeGeofence
            .map { geofence =>
              calculateError(pte, geofence)
            }
            .getOrElse(Array(PointInfo(0.0, 0.0), PointInfo(0.0, 0.0)))
          error
        }
      }
      val errors = geofenceErrorPerPte.flatten.flatten.filter(p => p.offset > 0)
      logger.info(
        s"Number of PTE for ride-hail is ${pathTraversalEvents.length}. There are ${errors.length} points which violate geofence"
      )
      logger.info("Stats about violations:")
      logger.info(s"Distance: ${Statistics(errors.map(_.offset))}")
      logger.info(s"Error(percent to the geofence radius): ${Statistics(errors.map(_.ratio * 100))}")
    } finally {
      Try(closable.close())
    }
  }

  def calculateError(pte: PathTraversalEvent, geofence: CircularGeofence): Array[PointInfo] = {
    val geofenceCoord = new Coord(geofence.geofenceX, geofence.geofenceY)
    val startUtm = geoUtils.wgs2Utm(new Coord(pte.startX, pte.startY))
    val endUtm = geoUtils.wgs2Utm(new Coord(pte.endX, pte.endY))
    val diffStart = GeoUtils.distFormula(geofenceCoord, startUtm) - geofence.geofenceRadius
    val diffEnd = GeoUtils.distFormula(geofenceCoord, endUtm) - geofence.geofenceRadius
    if (diffStart > 0)
      logger.info(
        s"Geofence is broken at start point. startOutsideOfGeofence: $diffStart"
      )
    if (diffEnd > 0)
      logger.info(
        s"Geofence is broken at end point. endOutsideOfGeofence: $diffEnd"
      )
    Array(PointInfo(diffStart, geofence.geofenceRadius), PointInfo(diffEnd, geofence.geofenceRadius))
  }
}
