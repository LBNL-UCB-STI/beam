package beam.utils.map

import java.io.{BufferedWriter, Closeable}

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
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.utils.io.IOUtils

case class Taz2TazWithPathTraversal(
   startTazId: Id[TAZ],
   endTazId: Id[TAZ],
   pathTraversalEvent: PathTraversalEvent,
   hour: Int = -1,
   observedTravelTime: Option[Float] = None
)
case class HourToTravelTime(hour: Int, travelTime: Float)

object TazTravelTimeAnalyzer extends LazyLogging {

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
    val (events: Iterator[Event], closable: Closeable) =
      EventReader.fromCsvFile("C:/temp/htt/0.events.csv.gz", filter)

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

          Taz2TazWithPathTraversal(startTaz.tazId, endTaz.tazId, pte)
        }
      }

      val withObservedTravelTimes: Array[Taz2TazWithPathTraversal] = tazWithPathTraversals
        .map { x =>
          val hour = x.pathTraversalEvent.departureTime / 3600
          val key = PathCache(x.startTazId, x.endTazId, hour)
          val travelTime = observedTravelTime.get(key)
          x.copy(hour = hour, observedTravelTime = travelTime)
        }
        .filter(x => x.observedTravelTime.isDefined)

      logger.info(s"withObservedTravelTimes size: ${withObservedTravelTimes.size}")

      val writer = IOUtils.getBufferedWriter("c:/temp/htt/tazODTravelTimeObservedVsSimulated_derived.csv")
      writer.write("fromTAZId,toTAZId,tazStartY,tazStartX,tazEndY,tazEndX,distance,linkIds,diffBetweenStart,diffBetweenEnd,pteStartY,pteStartX,pteEndY,pteEndX,departureTime,hour,timeSimulated,timeObserved,counts")
      writer.write("\n")

      withObservedTravelTimes
        .withFilter(x => x.pathTraversalEvent.departureTime >= 0 && x.pathTraversalEvent.departureTime <= 3600 * 23)
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

          writer.write(ot.pathTraversalEvent.legLength.toString)
          writer.write(',')

          writer.write(ot.pathTraversalEvent.linkIds.mkString(" ").toString)
          writer.write(',')

          val wgsPteStart = new Coord(ot.pathTraversalEvent.startX, ot.pathTraversalEvent.startY)
          val startDiff = geoUtils.distUTMInMeters(startTaz.coord, geoUtils.wgs2Utm(wgsPteStart))
          writer.write(startDiff.toString)
          writer.write(',')

          val wgsPteEnd = new Coord(ot.pathTraversalEvent.endX, ot.pathTraversalEvent.endY)
          val endDiff = geoUtils.distUTMInMeters(endTaz.coord, geoUtils.wgs2Utm(wgsPteEnd))
          writer.write(endDiff.toString)
          writer.write(',')

          writeCoord(writer, wgsPteStart)

          writeCoord(writer, wgsPteEnd)

          writer.write(ot.pathTraversalEvent.departureTime.toString)
          writer.write(',')

          writer.write(ot.hour.toString)
          writer.write(',')

          val simulatedTravelTime = (ot.pathTraversalEvent.arrivalTime - ot.pathTraversalEvent.departureTime).toString
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

//  private def average(xs: Array[Taz2TazWithPathTraversal]): Taz2TazWithPathTraversal = {
// withObservedTravelTimes can contain multiple entries per (startTazId, endTazId, hour). We need average them
//    val observed = xs.flatMap(_.observedTravelTime)
//    val totalObserved = observed.sum
//    val avgObserved = totalObserved / observed.length
//
//    val simulated = xs.map(x => x.pathTraversalEvent.arrivalTime - x.pathTraversalEvent.departureTime)
//
//  }

  private def writeCoord(writer: BufferedWriter, wgsCoord: Coord): Unit = {
    writer.write(wgsCoord.getY.toString)
    writer.write(',')

    writer.write(wgsCoord.getX.toString)
    writer.write(',')
  }

  private def wgsToUtm(geoUtils: GeoUtils, x: Double, y: Double): Coord = {
    val startWgsCoord = new Coord(x, y)
    val startUtmCoord = geoUtils.wgs2Utm(startWgsCoord)
    startUtmCoord
  }

  private def getObservedTravelTime(
    beamConfig: BeamConfig,
    geoUtils: GeoUtils,
    tazTreeMap: TAZTreeMap
  ): Map[PathCache, Float] = {

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
