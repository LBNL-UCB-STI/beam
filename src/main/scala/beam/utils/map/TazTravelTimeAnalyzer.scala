package beam.utils.map

import java.io.Closeable

import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.infrastructure.TAZTreeMap
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.TravelTimeObserved
import beam.router.TravelTimeObserved.PathCache
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.map.R5NetworkPlayground.prepareConfig
import beam.utils.{EventReader, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event

case class Taz2TazWithPathTraversal(startTaz: TAZ, endTaz: TAZ, pathTraversalEvent: PathTraversalEvent)
case class HourToTravelTime(hour: Int, travelTime: Float)

object TazTravelTimeAnalyzer extends LazyLogging{
  def filter(event: Event): Boolean = {
    // We need only PathTraversal with mode `CAR`
    val isNeededEvent = event.getEventType == "PathTraversal" && Option(event.getAttributes.get("mode")).contains("car")
    isNeededEvent
  }

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, true)
    val beamConfig = BeamConfig(cfg)
    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }
    val tazTreeMap: TAZTreeMap = BeamServices.getTazTreeMap(beamConfig.beam.agentsim.taz.filePath)

    val observedTravelTime: Map[PathCache, Float] = getObservedTravelTime(beamConfig, geoUtils, tazTreeMap)

    // This is lazy, it just creates an iterator
    val (events: Iterator[Event], closable: Closeable) = EventReader.fromCsvFile("C:/repos/beam-data-analysis/0.events.csv.gz", filter)

    try {
      // Actual reading happens here because we force computation by `toArray`
      val pathTraversalEvents = ProfilingUtils.timed("Read PathTraversal and filter by mode", x => logger.info(x)) {
        events.map(PathTraversalEvent.apply).toArray
      }

      logger.info(s"pathTraversalEvents size: ${pathTraversalEvents.length}")

      val tazWithPathTraversals = ProfilingUtils.timed("Get TAZ per PathTraversalEvent", x => logger.info(x)) {
        pathTraversalEvents.map { pte =>
          val startUtmCoord: Coord = wgsToUtm(geoUtils, pte.startX, pte.startY)
          val startTaz = tazTreeMap.getTAZ(startUtmCoord.getX, startUtmCoord.getY)

          val endUtmCoord: Coord = wgsToUtm(geoUtils, pte.endX, pte.endY)
          val endTaz = tazTreeMap.getTAZ(endUtmCoord.getX, endUtmCoord.getY)

          Taz2TazWithPathTraversal(startTaz, endTaz, pte)
        }
      }

      val withObservedTravelTimes: Array[(Taz2TazWithPathTraversal, Array[HourToTravelTime])] = tazWithPathTraversals.map { x =>
        val rngSeconds = Range(x.pathTraversalEvent.departureTime, x.pathTraversalEvent.arrivalTime, 3600)
        val acrossHours = rngSeconds.map { time => time / 3600 }.toArray

        val hourToObserved = acrossHours.flatMap { hour =>
          val key = PathCache(x.startTaz.tazId, x.endTaz.tazId, hour)
          observedTravelTime.get(key).map(travelTime => HourToTravelTime(hour, travelTime))
        }
        if (hourToObserved.length >= 2)
          logger.info(hourToObserved.toList.toString())
        x -> hourToObserved
      }.filter { case (taz, xs) => xs.nonEmpty }

      logger.info(s"tazWithPathTraversals size: ${tazWithPathTraversals.size}")
      logger.info(s"withObservedTravelTimes size: ${withObservedTravelTimes.size}")

      logger.info(withObservedTravelTimes.take(10).toList.toString)
    }
    finally {
      closable.close()
    }
  }

  private def wgsToUtm(geoUtils: GeoUtils, x: Double, y: Double): Coord = {
    val startWgsCoord = new Coord(x, y)
    val startUtmCoord = geoUtils.wgs2Utm(startWgsCoord)
    startUtmCoord
  }

  private def getObservedTravelTime(beamConfig: BeamConfig, geoUtils: GeoUtils, tazTreeMap: TAZTreeMap): Map[PathCache, Float] = {

    val zoneBoundariesFilePath = beamConfig.beam.calibration.roadNetwork.travelTimes.zoneBoundariesFilePath
    val zoneODTravelTimesFilePath = beamConfig.beam.calibration.roadNetwork.travelTimes.zoneODTravelTimesFilePath

    if (zoneBoundariesFilePath.nonEmpty && zoneODTravelTimesFilePath.nonEmpty) {
      val tazToMovId: Map[TAZ, Int] = TravelTimeObserved.buildTAZ2MovementId(
        zoneBoundariesFilePath,
        geoUtils,
        tazTreeMap
      )
      val movId2Taz: Map[Int, TAZ] = tazToMovId.map { case (k, v) => v -> k }
      TravelTimeObserved.buildPathCache2TravelTime(zoneODTravelTimesFilePath, movId2Taz)
    } else throw new RuntimeException("check file exists")

  }
}
