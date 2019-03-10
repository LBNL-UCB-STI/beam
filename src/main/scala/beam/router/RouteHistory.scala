package beam.router

import java.io.{BufferedInputStream, FileInputStream, FileReader, InputStreamReader, Reader}
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject

import scala.collection.concurrent.TrieMap

import beam.router.RouteHistory._
import beam.sim.config.BeamConfig
import beam.sim.BeamWarmStart
import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference
import probability_monad.Distribution

class RouteHistory @Inject()(
  beamConfig: BeamConfig
) extends IterationEndsListener
    with LazyLogging {

  private var previousRouteHistory: TrieMap[TimeBin, TrieMap[OriginTazId, TrieMap[DestTazId, Routes]]] =
    loadPreviousRouteHistory()
  private var routeHistory: TrieMap[TimeBin, TrieMap[OriginTazId, TrieMap[DestTazId, Routes]]] = TrieMap()
  private val randUnif = Distribution.uniform
  @volatile private var cacheRequests = 0
  @volatile private var cacheHits = 0

  def loadPreviousRouteHistory(): TrieMap[TimeBin, TrieMap[OriginTazId, TrieMap[DestTazId, IndexedSeq[LinkId]]]] = {
    if (beamConfig.beam.warmStart.enabled) {
      routeHistoryFilePath
        .map(RouteHistory.readCsvFile)
        .getOrElse(TrieMap.empty)
    } else {
      TrieMap.empty
    }
  }

  private def routeHistoryFilePath: Option[String] = {
    val maxHour = TimeUnit.SECONDS.toHours(new TravelTimeCalculatorConfigGroup().getMaxTime).toInt
    BeamWarmStart(beamConfig, maxHour).getWarmStartFilePath(RouteHistory.outputFileName)
  }

  private def timeToBin(departTime: Int): Int = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def rememberRoute(route: IndexedSeq[Int], departTime: Int): Unit = {
    val timeBin = timeToBin(departTime)
    routeHistory.get(timeBin) match {
      case Some(subMap) =>
        subMap.get(route.head) match {
          case Some(subSubMap) =>
            subSubMap.put(route.last, route)
          case None =>
            subMap.put(route.head, TrieMap(route.last -> route))
        }
      case None =>
        routeHistory.put(timeBin, TrieMap(route.head -> TrieMap(route.last -> route)))
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
    val fileHeader = "timeBin,originTAZId,destTAZId,route"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      RouteHistory.outputFileBaseName + ".csv.gz"
    )

    val flattenedRouteHistory: Iterable[(TimeBin, OriginTazId, DestTazId, String)] = routeHistory.flatMap {
      case (timeBin: TimeBin, origins: TrieMap[OriginTazId, TrieMap[DestTazId, Routes]]) =>
        origins.flatMap {
          case (originTazId: OriginTazId, destinations: TrieMap[DestTazId, Routes]) =>
            destinations.flatMap {
              case (destTazId: DestTazId, path: Routes) =>
                Some(timeBin, originTazId, destTazId, path.mkString(":"))
            }
        }
    }
    val csvContent = flattenedRouteHistory.view
      .map { tuple =>
        s"${tuple._1},${tuple._2},${tuple._3},${tuple._4}"
      }
      .mkString("\n")

    FileUtils.writeToFile(
      filePath,
      Some(fileHeader),
      csvContent,
      None
    )
    previousRouteHistory = routeHistory
    routeHistory = new TrieMap()
  }

}

object RouteHistory {
  type TimeBin = Int
  type OriginTazId = Int
  type DestTazId = Int
  type LinkId = Int
  type Routes = IndexedSeq[LinkId]

  private val outputFileBaseName = "routeHistory"
  private val outputFileName = outputFileBaseName + ".csv.gz"

  private def readCsvFile(filePath: String): TrieMap[Int, TrieMap[Int, TrieMap[Int, IndexedSeq[Int]]]] = {
    var mapReader: ICsvMapReader = null
    val result = TrieMap[Int, TrieMap[Int, TrieMap[Int, IndexedSeq[Int]]]]()
    try {
      val reader = buildReader(filePath)
      mapReader = new CsvMapReader(reader, CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val timeBin = line.get("timeBin").toInt
        val origTazId = line.get("originTAZId").toInt
        val destTazId = line.get("destTAZId").toInt
        val route: IndexedSeq[Int] = line
          .get("route")
          .split(":")
          .map(_.toInt)

        val timeBinReference = result.getOrElseUpdate(
          timeBin,
          TrieMap(origTazId -> TrieMap(destTazId -> route))
        )

        val originReference = timeBinReference.getOrElseUpdate(
          origTazId,
          TrieMap(destTazId -> route)
        )

        originReference.update(destTazId, route)

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
