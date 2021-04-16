package beam.router

import java.io._
import java.util.zip.GZIPInputStream
import javax.inject.Inject

import scala.collection.concurrent.TrieMap

import beam.router.RouteHistory.{RouteHistoryADT, _}
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import com.google.common.escape.ArrayBasedUnicodeEscaper
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference
import probability_monad.Distribution

class RouteHistory @Inject()(
  beamConfig: BeamConfig
) extends IterationEndsListener
    with LazyLogging {

  private var routeHistory: RouteHistoryADT = TrieMap()
  private val randUnif = Distribution.uniform
  @volatile private var cacheRequests = 0
  @volatile private var cacheHits = 0

  private def timeToBin(departTime: Int): Int = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def rememberRoute(route: IndexedSeq[Int], departTime: Int): Unit = {
    val timeBin = timeToBin(departTime)
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val routeHead = route.head
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val routeLast = route.last
    routeHistory.get(timeBin) match {
      case Some(subMap) =>
        subMap.get(routeHead) match {
          case Some(subSubMap) =>
            subSubMap.put(routeLast, route)
          case None =>
            subMap.put(routeHead, TrieMap(routeLast -> route))
        }
      case None =>
        routeHistory.put(timeBin, TrieMap(routeHead -> TrieMap(routeLast -> route)))
    }
  }

  def getRoute(orig: Int, dest: Int, time: Int): Option[IndexedSeq[Int]] = {
    cacheRequests += 1
    val timeBin = timeToBin(time)
    routeHistory.get(timeBin) match {
      case Some(subMap) =>
        subMap.get(orig) match {
          case Some(subSubMap) =>
            cacheHits += 1
            subSubMap.get(dest)
          case None =>
            None
        }
      case None =>
        None
    }
  }

  def expireRoutes(fracToExpire: Double): Unit = {
    logger.info(
      "Overall cache hits {}/{} ({}%)",
      cacheHits,
      cacheRequests,
      Math.round(cacheHits.toDouble / cacheRequests.toDouble * 100)
    )
    cacheRequests = 0
    cacheHits = 0
    routeHistory = TrieMap()
    val fracAtEachLevel = Math.pow(fracToExpire, 0.33333)
    routeHistory.keys.foreach { key1 =>
      if (randUnif.get < fracAtEachLevel) {
        routeHistory(key1).keys.foreach { key2 =>
          if (randUnif.get < fracAtEachLevel) {
            routeHistory(key1)(key2).keys.foreach { key3 =>
              if (randUnif.get < fracAtEachLevel) {
                routeHistory(key1)(key2).remove(key3)
              }
            }
          }
        }
      }
    }
  }
  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {

    if (shouldWriteInIteration(event.getIteration, beamConfig.beam.physsim.writeRouteHistoryInterval)) {
      val filePath = event.getServices.getControlerIO.getIterationFilename(
        event.getServices.getIterationNumber,
        "routeHistory.csv.gz"
      )

      FileUtils.writeToFile(
        filePath,
        toCsv(routeHistory),
      )
    }

    routeHistory = new TrieMap()
  }

  private def shouldWriteInIteration(iterationNumber: Int, interval: Int): Boolean = {
    interval == 1 || (interval > 0 && iterationNumber % interval == 0)
  }
}

object RouteHistory {
  type TimeBin = Int
  type OriginLinkId = Int
  type DestLinkId = Int
  type LinkId = Int
  type Route = IndexedSeq[LinkId]
  type RouteHistoryADT = TrieMap[TimeBin, TrieMap[OriginLinkId, TrieMap[DestLinkId, Route]]]

  private val CsvHeader: String = "timeBin,originLinkId,destLinkId,route"
  private val Eol: String = "\n"

  private[router] def toCsv(routeHistory: RouteHistoryADT): Iterator[String] = {
    val flattenedRouteHistory: Iterator[(TimeBin, OriginLinkId, DestLinkId, String)] = routeHistory.toIterator.flatMap {
      case (timeBin: TimeBin, origins: TrieMap[OriginLinkId, TrieMap[DestLinkId, Route]]) =>
        origins.flatMap {
          case (originLinkId: OriginLinkId, destinations: TrieMap[DestLinkId, Route]) =>
            destinations.flatMap {
              case (destLinkId: DestLinkId, path: Route) =>
                Some(timeBin, originLinkId, destLinkId, path.mkString(":"))
            }
        }
    }
    val body: Iterator[String] = flattenedRouteHistory
      .map {
        case (timeBin, originLinkId, destLinkId, route) =>
          s"$timeBin,$originLinkId,$destLinkId,$route$Eol"
      }
    Iterator(CsvHeader, Eol) ++ body
  }

  private[router] def fromCsv(filePath: String): RouteHistoryADT = {
    var mapReader: ICsvMapReader = null
    val result = TrieMap[TimeBin, TrieMap[OriginLinkId, TrieMap[DestLinkId, Route]]]()
    try {
      val reader = buildReader(filePath)
      mapReader = new CsvMapReader(reader, CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val timeBin = line.get("timeBin").toInt
        val origLinkId = line.get("originLinkId").toInt
        val destLinkId = line.get("destLinkId").toInt
        val route: IndexedSeq[Int] = line
          .get("route")
          .split(":")
          .map(_.toInt)

        val timeBinReference = result.getOrElseUpdate(
          timeBin,
          TrieMap(origLinkId -> TrieMap(destLinkId -> route))
        )

        val originReference = timeBinReference.getOrElseUpdate(
          origLinkId,
          TrieMap(destLinkId -> route)
        )
        originReference.update(destLinkId, route)

        line = mapReader.read(header: _*)
      }

    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    result
  }

  private def buildReader(filePath: String): Reader = {
    if (filePath.endsWith(".gz")) {
      new InputStreamReader(
        new GZIPInputStream(new BufferedInputStream(new FileInputStream(filePath)))
      )
    } else {
      new FileReader(filePath)
    }
  }

}
