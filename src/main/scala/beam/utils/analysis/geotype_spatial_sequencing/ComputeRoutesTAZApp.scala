package beam.utils.analysis.geotype_spatial_sequencing

import java.io.File
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}

import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.common.GeoUtils
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.concurrent.{blocking, Await, ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

object ComputeRoutesTAZApp extends LazyLogging {
  private val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26914"
  }

  case class OutputResult(src: String, dest: String, distanceMeters: Double, timeSeconds: Double, googleLink: String)

  implicit val ex: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val totalNumberOfThrottling: AtomicInteger = new AtomicInteger(0)

  def throttleIfWritingIsSlower(computed: Int, written: Int, sleepTimeMs: Int = 20): Unit = {
    // Number of computed can be X percent higher than number of written
    val allowedPct = 3.toDouble
    val lowerBound = written
    val upperBound = written + written * (allowedPct / 100)
    val isInSafeRange = computed >= lowerBound && computed <= upperBound
    if (!isInSafeRange) {
      val tCnt = totalNumberOfThrottling.getAndIncrement()
      if (tCnt % 5000 == 0) {
        logger.warn(
          s"Number of computed routes: $computed, number of written routes: $written, allowed % is $allowedPct [$lowerBound, $upperBound]. Total number of throttles: $tCnt"
        )
      }
      // Need to throttle
      Thread.sleep(sleepTimeMs)
    }
  }

  def main(args: Array[String]): Unit = {
    val pathToTaz = "D:/Work/beam/DallasGraphHooper/tazCentersDallas_study_area.csv" // args(0)
    val pathToGh = "D:/Work/beam/DallasGraphHooper/graphhopper-web/graph-cache" // args(2)
    val tempOutputPath = "D:/Work/beam/DallasGraphHooper/" //  args(3)

    val tazTreeMap = ProfilingUtils.timed("Read TAZs", x => logger.info(x)) {
      TAZTreeMap.fromCsv(pathToTaz)
    }

    println(s"Tazs: ${tazTreeMap.getTAZs.size}")

    val trips = (for {
      src <- tazTreeMap.getTAZs
      dsc <- tazTreeMap.getTAZs
    } yield Trip(src.tazId.toString, dsc.tazId.toString, 1)).toArray

    val routeResolver = new RouteResolverTAZ(pathToGh)

    logger.info(s"Read ${trips.length} tips")
    logger.info(s"routeResolver: ${routeResolver}")

    val toProcess = trips
    val numberOfRequest: Int = toProcess.length
    val totalComputedRoutes: AtomicInteger = new AtomicInteger(0)
    val totalFailedRoutes: AtomicInteger = new AtomicInteger(0)
    val totalWrittenResults: AtomicInteger = new AtomicInteger(0)

    val nThreads = 7

    val binToTrip: Array[(Int, Trip)] = trips.map { trip =>
      (getBin(trip, nThreads), trip)
    }

    val binToTrips: Array[(String, Array[Trip])] =
      binToTrip.groupBy { case (bin, _) => bin }.map { case (bin, xs) => bin.toString -> xs.map(_._2) }.toArray

    val outputPath = prepareOutputPath(tempOutputPath, binToTrips.map(_._1.toString))
    logger.info(s"outputPath: ${outputPath}")

    val stateToResultQueue = binToTrips.map {
      case (bin, _) =>
        (bin, new ConcurrentLinkedQueue[OutputResult]())
    }
    val stateToQueueMap = stateToResultQueue.toMap

    val hasDone: AtomicBoolean = new AtomicBoolean(false)
    val nWritingThreads: Int = 6
    val forWritingGrouped = stateToResultQueue.grouped(stateToResultQueue.length / nWritingThreads).toArray
    logger.info(s"Number of writing threads: $nWritingThreads")

    val writeFutures = forWritingGrouped.map { group =>
      blocking {
        Future {
          try {
            val stateToWriter: Map[String, CsvWriter] = group
              .map(_._1)
              .map { state =>
                val stateOutputPath = s"$outputPath/$state/result.csv"
                state -> new CsvWriter(
                  stateOutputPath,
                  Vector("source", "destination", "distanceMeters", "timeSeconds", "googleLink")
                )
              }
              .toMap
            while (!hasDone.get()) {
              writeResults(totalWrittenResults, numberOfRequest, group, stateToWriter)
            }
            // Need to try to write what is left
            writeResults(totalWrittenResults, numberOfRequest, group, stateToWriter)

            stateToWriter.foreach { case (_, wrt) => Try(wrt.close()) }
          } catch {
            case NonFatal(ex) =>
              logger.error(s"Could not create a writter: ${ex.getMessage}", ex)
              ex.printStackTrace()
              System.exit(1)
              Thread.sleep(3000)
          }
        }
      }
    }.toList

    val perThreadPortion = toProcess.length / nThreads
    logger.info(s"nThreads: $nThreads, perThreadPortion: $perThreadPortion")
    val groupedPerThread = toProcess.grouped(perThreadPortion).zipWithIndex

    val s = System.currentTimeMillis()
    val futures = groupedPerThread.map {
      case (trips, groupIndex) =>
        Future {
          trips.foreach { trip =>
            throttleIfWritingIsSlower(totalComputedRoutes.get(), totalWrittenResults.get())
            val maybeRoute = calcRoute(routeResolver, tazTreeMap, trip)
            maybeRoute match {
              case Some(route) =>
                val bin = getBin(trip, nThreads)
                stateToQueueMap(bin.toString).add(route)
                totalComputedRoutes.getAndIncrement()
              case None =>
                totalFailedRoutes.incrementAndGet()
            }
            val processed = totalComputedRoutes.get()
            if (processed % onePctNumber(numberOfRequest) == 0) {
              val pct = 100 * processed.toDouble / numberOfRequest
              val tookSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - s)
              val msg =
                s"Processed $processed out of $numberOfRequest => $pct % in $tookSeconds seconds. Number of failed routes: $totalFailedRoutes"
              println(msg)
              logger.info(msg)
            }
          }
        }
    }.toList
    Await.result(Future.sequence(futures), Duration.Inf)
    hasDone.set(true)
    logger.info("Done with computing the routes")

    logger.info("Waiting for the writers...")
    // Now wait when writer will finish
    Await.result(Future.sequence(writeFutures), Duration.Inf)

    logger.info(s"totalFailedRoutes: $totalFailedRoutes")
    logger.info(s"totalComputedRoutes: $totalComputedRoutes")
    logger.info(s"totalWrittenResults: $totalWrittenResults")
    logger.info("Done")
  }

  def onePctNumber(n: Int): Int = (n * 0.01).toInt

  def getBin(trip: Trip, nThreads: Int): Int = {
    val compoundKey = s"${trip.origin}-${trip.dest}"
    val hash = compoundKey.hashCode
    val bin = hash % nThreads
    bin
  }

  def writeResults(
    written: AtomicInteger,
    numberOfRequest: Int,
    group: Array[(String, ConcurrentLinkedQueue[OutputResult])],
    stateToWriter: Map[String, CsvWriter]
  ): Unit = {
    group.foreach {
      case (state, queue) =>
        val csvWriter = stateToWriter(state)
        var route: OutputResult = null
        do {
          route = queue.poll()
          if (route != null) {
            csvWriter.write(route.src, route.dest, route.distanceMeters, route.timeSeconds, route.googleLink)
            val totalWritten = written.incrementAndGet()
            if (totalWritten % onePctNumber(numberOfRequest) == 0) {
              val pct = 100 * totalWritten.toDouble / numberOfRequest
              val msg = s"Written $totalWritten out of $numberOfRequest => $pct %"
              println(msg)
              logger.info(msg)
            }
          }
        } while (route != null)
    }
  }

  def calcRoute(
    routeResolver: RouteResolverTAZ,
    tazTreeMap: TAZTreeMap,
    trip: Trip
  ): Option[OutputResult] = {
    val originTaz = tazTreeMap.getTAZ(trip.origin).get
    val destTaz = tazTreeMap.getTAZ(trip.dest).get
    val originWGS = geoUtils.utm2Wgs(originTaz.coord)
    val destWGS = geoUtils.utm2Wgs(destTaz.coord)

    val resp = routeResolver.route(originWGS, destWGS)
    if (resp.hasErrors || resp.getAll.size() == 0) {
      None
    } else {
      val best = resp.getBest
      // We have chosen 2019-10-16 03:00am (Wednesday) as a day to get the routes from Google. It is 1571194800 in Unix time
      val timeEpochSeconds = 1571194800
      val googleLink =
        s"https://www.google.com/maps/dir/${originWGS.getY}%09${originWGS.getX}/${destWGS.getY}%09${destWGS.getX}/data=!3m1!4b1!4m15!4m14!1m3!2m2!1d-97.7584165!2d30.3694661!1m3!2m2!1d-97.7295503!2d30.329807!2m4!2b1!6e0!7e2!8j${timeEpochSeconds}!3e0"
      Some(
        OutputResult(
          src = originTaz.tazId.toString,
          dest = destTaz.tazId.toString,
          distanceMeters = best.getDistance,
          timeSeconds = best.getTime / 1000,
          googleLink = googleLink
        )
      )
    }
  }

  def prepareOutputPath(outputPath: String, uniqueBins: Seq[String]): String = {
    val resultPath = s"${outputPath}/result"
    if (new File(resultPath).exists()) {
      throw new IllegalStateException(
        s"Result path '${resultPath}' already exists! Please, remove it and re-run again!"
      )
    } else {
      new File(resultPath).mkdir()
    }
    uniqueBins.foreach { state =>
      val fullPath = s"$resultPath/${state}"
      val file = new File(fullPath)
      if (file.exists()) {
        throw new IllegalStateException(
          s"Result path for the state '${fullPath}' already exists! Please, remove it and re-run again!"
        )
      }
      file.mkdir()
      logger.debug(s"Created $fullPath")
    }
    resultPath
  }
}
