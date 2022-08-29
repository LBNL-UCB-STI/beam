package beam.utils.map

import java.io.{BufferedWriter, Closeable}

import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.infrastructure.taz._
import beam.router.skim.SkimsUtils
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.map.R5NetworkPlayground.prepareConfig
import beam.utils.{EventReader, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.io.IOUtils

case class Taz2TazWithTrip(
  startTazId: Id[TAZ],
  endTazId: Id[TAZ],
  trip: Trip,
  hour: Int = -1,
  observedTravelTime: Option[Float] = None
)
case class HourToTravelTime(hour: Int, travelTime: Float)

case class Trip(
  departureTime: Int,
  arrivalTime: Int,
  wgsStartCoord: Coord,
  wgsEndCoord: Coord,
  legs: IndexedSeq[PathTraversalEvent]
) {
  val travelTime: Int = arrivalTime - departureTime
  lazy val tripLinks: IndexedSeq[Int] = legs(0).linkIds ++ legs(1).linkIds
  lazy val tripLength: Double = legs(0).legLength + legs(1).legLength
}

object Trip {

  def apply(legs: IndexedSeq[PathTraversalEvent]): Trip = {
    assert(legs.size == 2, "There should be only 2 path traversal")
    val sortedByDeparture = legs.sortBy(x => x.departureTime)
    val departureTime = legs(0).departureTime
    val arrivalTime = legs(1).arrivalTime
    val wgsStartCoord = new Coord(legs(0).startX, legs(0).startY)
    val wgsEndCoord = new Coord(legs(1).endX, legs(1).endY)
    Trip(departureTime, arrivalTime, wgsStartCoord, wgsEndCoord, sortedByDeparture)
  }
}

object TazTravelTimeAnalyzer extends LazyLogging {

  import SkimsUtils._

  def filter(event: Event): Boolean = {
    val attribs = event.getAttributes
    // We need only PathTraversal with mode `CAR`, no ride hail
    val isNeededEvent = event.getEventType == "PathTraversal" && Option(attribs.get("mode")).contains("car") &&
      !Option(attribs.get("vehicle")).exists(vehicle => vehicle.contains("rideHailVehicle-"))
    isNeededEvent
  }

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, true)
    val beamConfig = BeamConfig(cfg)
    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }
    val tazTreeMap: TAZTreeMap = TAZTreeMap.getTazTreeMap(beamConfig.beam.agentsim.taz.filePath)

    val observedTravelTime: Map[PathCache, Float] = getObservedTravelTime(beamConfig, geoUtils, tazTreeMap)

    // This is lazy, it just creates an iterator
    val (events: Iterator[Event], closable: Closeable) =
      EventReader.fromCsvFile("C:/temp/travel_time_investigation/0.events.csv", filter)

    try {
      // Actual reading happens here because we force computation by `toArray`
      val pathTraversalEvents = ProfilingUtils.timed("Read PathTraversal and filter by mode", x => logger.info(x)) {
        events.map(PathTraversalEvent.apply(_)).toArray
      }
      logger.info(s"pathTraversalEvents size: ${pathTraversalEvents.length}")

      val trips = pathTraversalEvents
        .groupBy { x =>
          x.vehicleId
        }
        .flatMap { case (_, xs) =>
          // sliding 2 by 2 because every trip must have two path traversal events (1 + parking)
          xs.sortBy(x => x.departureTime).sliding(2, 2).map { legs =>
            Trip(legs)
          }
        }
        .toArray

      val tazWithTrips = ProfilingUtils.timed("Get TAZ per trip", x => logger.info(x)) {
        trips.map { trip =>
          val startUtmCoord: Coord = geoUtils.wgs2Utm(trip.wgsStartCoord)
          val startTaz = tazTreeMap.getTAZ(startUtmCoord.getX, startUtmCoord.getY)

          val endUtmCoord: Coord = geoUtils.wgs2Utm(trip.wgsEndCoord)
          val endTaz = tazTreeMap.getTAZ(endUtmCoord.getX, endUtmCoord.getY)

          Taz2TazWithTrip(startTaz.tazId, endTaz.tazId, trip)
        }
      }

      val withObservedTravelTimes: Array[Taz2TazWithTrip] = tazWithTrips
        .map { x =>
          val hour = x.trip.departureTime / 3600
          val key = PathCache(x.startTazId, x.endTazId, hour)
          val travelTime = observedTravelTime.get(key)
          x.copy(hour = hour, observedTravelTime = travelTime)
        }
        .filter(x => x.observedTravelTime.isDefined)

      logger.info(s"withObservedTravelTimes size: ${withObservedTravelTimes.length}")

      val writer =
        IOUtils.getBufferedWriter("c:/temp/travel_time_investigation/tazODTravelTimeObservedVsSimulated_derived.csv")
      writer.write(
        "fromTAZId,toTAZId,tazStartY,tazStartX,tazEndY,tazEndX,distance,linkIds,diffBetweenStart,diffBetweenEnd,pteStartY,pteStartX,pteEndY,pteEndX,departureTime,hour,timeSimulated,timeObserved,counts"
      )
      writer.write("\n")

      withObservedTravelTimes
        .withFilter(x => x.trip.departureTime >= 0 && x.trip.departureTime <= 3600 * 23)
        .foreach { ot =>
          writer.write(ot.startTazId.toString)
          writer.write(',')

          writer.write(ot.endTazId.toString)
          writer.write(',')

          val startTaz = tazTreeMap.getTAZ(ot.startTazId).get
          val wgsStartCoord = geoUtils.utm2Wgs(startTaz.coord)

          writeCoord(writer, wgsStartCoord)

          val endTaz = tazTreeMap.getTAZ(ot.endTazId).get
          val wgsEndCoord = geoUtils.utm2Wgs(endTaz.coord)
          writeCoord(writer, wgsEndCoord)

          writer.write(ot.trip.tripLength.toString)
          writer.write(',')

          writer.write(ot.trip.tripLinks.mkString(" "))
          writer.write(',')

          val startDiff = geoUtils.distUTMInMeters(startTaz.coord, geoUtils.wgs2Utm(ot.trip.wgsStartCoord))
          writer.write(startDiff.toString)
          writer.write(',')

          val endDiff = geoUtils.distUTMInMeters(endTaz.coord, geoUtils.wgs2Utm(ot.trip.wgsEndCoord))
          writer.write(endDiff.toString)
          writer.write(',')

          writeCoord(writer, ot.trip.wgsStartCoord)

          writeCoord(writer, ot.trip.wgsEndCoord)

          writer.write(ot.trip.departureTime.toString)
          writer.write(',')

          writer.write(ot.hour.toString)
          writer.write(',')

          val simulatedTravelTime = ot.trip.travelTime.toString
          writer.write(simulatedTravelTime)
          writer.write(',')

          writer.write(ot.observedTravelTime.get.toString)
          writer.write(',')

          writer.write("1")
          writer.newLine()
        }

      writer.flush()
      writer.close()

      logger.info(withObservedTravelTimes.take(10).toList.toString)
    } finally {
      closable.close()
    }
  }

  private def writeCoord(writer: BufferedWriter, wgsCoord: Coord): Unit = {
    writer.write(wgsCoord.getY.toString)
    writer.write(',')

    writer.write(wgsCoord.getX.toString)
    writer.write(',')
  }

  private def getObservedTravelTime(
    beamConfig: BeamConfig,
    geoUtils: GeoUtils,
    tazTreeMap: TAZTreeMap
  ): Map[SkimsUtils.PathCache, Float] = {

    val maxDistanceFromBeamTaz: Double = 500.0 // 500 meters

    val zoneBoundariesFilePath = beamConfig.beam.calibration.roadNetwork.travelTimes.zoneBoundariesFilePath
    val zoneODTravelTimesFilePath = beamConfig.beam.calibration.roadNetwork.travelTimes.zoneODTravelTimesFilePath

    if (zoneBoundariesFilePath.nonEmpty && zoneODTravelTimesFilePath.nonEmpty) {
      val tazToMovId: Map[TAZ, Int] = buildTAZ2MovementId(
        zoneBoundariesFilePath,
        geoUtils,
        tazTreeMap,
        maxDistanceFromBeamTaz
      )
      val movId2Taz: Map[Int, TAZ] = tazToMovId.map { case (k, v) => v -> k }
      buildPathCache2TravelTime(zoneODTravelTimesFilePath, movId2Taz)
    } else throw new RuntimeException("check file exists")

  }
}
