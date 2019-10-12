package beam.utils.map

import java.io.Closeable

import beam.agentsim.events.PathTraversalEvent
import beam.sim.common.GeoUtils
import beam.sim.{Geofence, RideHailFleetInitializer}
import beam.utils.{EventReader, ProfilingUtils, Statistics}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event

import scala.util.Try

object GeoFenceVerifier extends LazyLogging {

  val geoUtils = new beam.sim.common.GeoUtils {
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
        case (Some(x), Some(y), Some(r)) => Some(Geofence(x, y, r))
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
      val distanceOutOfGeofenceWithError = pathTraversalEvents.map { pte =>
        vehIdToGeofence.get(pte.vehicleId.toString).map { maybeGeofence =>
          val error = maybeGeofence
            .map { geofence =>
              calculateError(pte, geofence)
            }
            .getOrElse((0.0, 0.0))
          error
        }
      }
      val totalNumberOfPTEOutOfGeofence = distanceOutOfGeofenceWithError.count(x => x.exists(z => z._1 > 0))
      val totalPTEWithGeofence = distanceOutOfGeofenceWithError.count(x => x.isDefined)
      val percent = 100 * totalNumberOfPTEOutOfGeofence.toDouble / totalPTEWithGeofence
      logger.info(
        f"Total number of ride-hail PTE which does not respect geofence: ${totalNumberOfPTEOutOfGeofence}, total number of ride-hail PTE : ${totalPTEWithGeofence} => $percent%.2f %%"
      )
      logger.info(s"Difference in the distance stats: ${Statistics(distanceOutOfGeofenceWithError.toVector.flatten.map { case (dist, _) => dist })}")
      logger.info(s"Error(percent to the geofence radius) stats: ${Statistics(
        distanceOutOfGeofenceWithError.flatten.map { case (_, ratio) => 100 * ratio }
      )}")
    } finally {
      Try(closable.close())
    }
  }

  def calculateError(pte: PathTraversalEvent, geofence: Geofence): (Double, Double) = {
    val geofenceCoord = new Coord(geofence.geofenceX, geofence.geofenceY)
    val startUtm = geoUtils.wgs2Utm(new Coord(pte.startX, pte.startY))
    val endUtm = geoUtils.wgs2Utm(new Coord(pte.endX, pte.endY))
    val diffStart = GeoUtils.distFormula(geofenceCoord, startUtm) - geofence.geofenceRadius
    val diffEnd = GeoUtils.distFormula(geofenceCoord, endUtm) - geofence.geofenceRadius
    val startOutsideOfGeofence = if (diffStart > 0) diffStart else 0
    val endOutsideOfGeofence = if (diffEnd > 0) diffEnd else 0
    if (startOutsideOfGeofence > 0)
      logger.info(
        s"Geofence is broken at start point. startOutsideOfGeofence: $startOutsideOfGeofence"
      )
    if (endOutsideOfGeofence > 0)
      logger.info(
        s"Geofence is broken at end point. endOutsideOfGeofence: $endOutsideOfGeofence"
      )
    val totalError = startOutsideOfGeofence + endOutsideOfGeofence
    (totalError, totalError / geofence.geofenceRadius)
  }
}
