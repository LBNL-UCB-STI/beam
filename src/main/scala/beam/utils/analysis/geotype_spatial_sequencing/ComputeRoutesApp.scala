package beam.utils.analysis.geotype_spatial_sequencing

import java.io.File
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, blocking}
import scala.util.Try
import scala.collection.JavaConverters._

object ComputeRoutesApp extends LazyLogging {
  implicit val ex: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  def main(args: Array[String]): Unit = {
    assert(args.size == 4)
    val pathToCencusTrack = args(0)
    val pathToOd = args(1)
    val pathToGh = args(2)
    val tempOutputPath = args(3)



    val tripsFuture = Future {
      ProfilingUtils.timed("Read the trips", x => logger.info(x)) {
        TripReader.readFromCsv(pathToOd)
      }
    }
    val censusTrackF = Future {
      ProfilingUtils.timed("Read the census tracks", x => logger.info(x)) {
        CencusTrackReader.readFromCsv(pathToCencusTrack)
      }
    }

    val routeResolverF = Future {
      new RouteResolver(pathToGh)
    }

    val List(tripsAny, censusTrackAny, routeResolverAny) =
      Await.result(Future.sequence(List(tripsFuture, censusTrackF, routeResolverF)), 1000.seconds)

    val trips = tripsAny.asInstanceOf[Array[Trip]]
    val censusTrack = censusTrackAny.asInstanceOf[Array[CencusTrack]]
    val routeResolver = routeResolverAny.asInstanceOf[RouteResolver]

    val censusTrackMap = censusTrack.groupBy(x => x.id).map {
      case (k, xs) =>
        if (xs.length > 1)
          logger.warn(s"Key $k mapped to more than 1 CencusTrack: ${xs.toVector}")
        k -> xs.head
    }

    logger.info(s"Read ${trips.length} tips")
    logger.info(s"Read ${censusTrack.length} census tracks")
    logger.info(s"routeResolver: ${routeResolver}")

    val uniqueValidStates = censusTrack.map(_.state).distinct
      .flatMap(s => Try(s.toInt).toOption).sorted
        .map("%02d".format(_))
    logger.info(s"Sorted unique valid states: ${uniqueValidStates.toVector}")
    val outputPath = prepareOutputPath(tempOutputPath, uniqueValidStates)
    logger.info(s"outputPath: ${outputPath}")

    val toProcess = trips
    val numberOfRequest: Int = toProcess.length
    val totalComputedRoutes: AtomicInteger = new AtomicInteger(0)
    val totalFailedRoutes: AtomicInteger = new AtomicInteger(0)
    val totalWrittenResults: AtomicInteger = new AtomicInteger(0)

    val perState: Map[String, Array[Trip]] = toProcess.groupBy { x =>
      val originState = x.origin.substring(0, 2)
      originState
    }.map { case (state, xs) =>
      state -> xs
    }
    logger.info(s"Source state to the number of trips: ")
    perState.toSeq.sortBy(x => -x._2.length).foreach { case (k, v) => logger.info(s"$k => ${v.size}")}

    val stateToResultQueue = uniqueValidStates.map { state =>
      (state,  new ConcurrentLinkedQueue[OutputResult]())
    }
    val stateToQueueMap = stateToResultQueue.toMap

    val hasDone: AtomicBoolean = new AtomicBoolean(false)
    // We want to have 8 writing threads
    val forWritingGrouped = stateToResultQueue.grouped(stateToResultQueue.length / 4).toArray

    val writeFutures = forWritingGrouped.map { group =>
      blocking {
        Future {
          val stateToWriter: Map[String, CsvWriter] = group.map(_._1).map { state =>
            val stateOutputPath = s"$outputPath/$state/result.csv.gz"
            state -> new CsvWriter(stateOutputPath, Vector("source", "destination", "distance", "ascend", "descend", "linestring"))
          }.toMap
          while (!hasDone.get()) {
            writeResults(totalWrittenResults, numberOfRequest, group, stateToWriter)
          }
          // Need to try to write what is left
          writeResults(totalWrittenResults, numberOfRequest, group, stateToWriter)

          stateToWriter.foreach { case (_, wrt) => Try(wrt.close())}
        }
      }
    }.toList

    val nThreads = 7
    val perThreadPortion = toProcess.length / nThreads
    logger.info(s"nThreads: $nThreads, perThreadPortion: $perThreadPortion")
    val groupedPerThread = toProcess.grouped(perThreadPortion).zipWithIndex

    val s = System.currentTimeMillis()
    val futures = groupedPerThread.map {
      case (trips, groupIndex) =>
        Future {
            trips.foreach { trip =>
              val maybeRoute = calcRoute(routeResolver, censusTrackMap, trip)
              maybeRoute match {
                case Some(route) =>
                  val state = route.origin.state
                  stateToQueueMap(state).add(route)
                  totalComputedRoutes.getAndIncrement()
                case None =>
                  totalFailedRoutes.incrementAndGet()
              }
              val processed = totalComputedRoutes.get()
              if (processed % onePctNumber(numberOfRequest) == 0) {
                val pct = 100 * processed.toDouble / numberOfRequest
                val tookSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - s)
                val msg = s"Processed $processed out of $numberOfRequest => $pct % in $tookSeconds seconds. Number of failed routes: $totalFailedRoutes"
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


  def writeResults(written: AtomicInteger, numberOfRequest: Int, group: Array[(String, ConcurrentLinkedQueue[OutputResult])],
                   stateToWriter: Map[String, CsvWriter]): Unit = {
    group.foreach { case (state, queue) =>
      val csvWriter = stateToWriter(state)
      var route: OutputResult = null
      do {
        route = queue.poll()
        if (route != null) {
          val escapedLineString = "\"" + route.lineString.toString + "\""
          csvWriter.write(route.origin.id, route.dest.id, route.distance, route.ascend, route.descend, escapedLineString)
          val totalWritten = written.incrementAndGet()
          if (totalWritten % onePctNumber(numberOfRequest) == 0) {
            val pct = 100 * totalWritten.toDouble / numberOfRequest
            val msg = s"Written $totalWritten out of $numberOfRequest => $pct %"
            println(msg)
            logger.info(msg)
          }
        }
      }
      while (route != null)
    }
  }

  def calcRoute(
    routeResolver: RouteResolver,
    censusTrackMap: Map[String, CencusTrack],
    trip: Trip
  ): Option[OutputResult] = {
    val originCensusTrack = censusTrackMap.get(trip.origin)
    val destCensusTrack = censusTrackMap.get(trip.dest)
    if (originCensusTrack.isEmpty) {
      logger.warn(s"Cannot find origin '${trip.origin}' in censusTrackMap")
    }
    if (destCensusTrack.isEmpty) {
      logger.warn(s"Cannot find dest '${trip.dest}' in censusTrackMap")
    }
    if (originCensusTrack.nonEmpty && destCensusTrack.nonEmpty) {
      val resp = routeResolver.route(originCensusTrack.get, destCensusTrack.get)
      if (resp.hasErrors || resp.getAll.size() == 0) {
        None
      } else {
        val finalPoint = resp.getAll.asScala.reduce((p1, p2) => if (p1.getPoints.size > p2.getPoints.size()) p1 else p2)
        val pointAsLineString = finalPoint.getPoints.toLineString(false)
        Some(OutputResult(origin = originCensusTrack.get, dest = destCensusTrack.get, distance = finalPoint.getDistance,
          ascend = finalPoint.getAscend, descend = finalPoint.getDescend, lineString = pointAsLineString))
      }
    } else None
  }

  def prepareOutputPath(outputPath: String, uniqueValidStates: Array[String]): String = {
    val resultPath = s"${outputPath}/result"
    if (new File(resultPath).exists()) {
      throw new IllegalStateException(s"Result path '${resultPath}' already exists! Please, remove it and re-run again!")
    } else {
      new File(resultPath).mkdir()
    }
    uniqueValidStates.foreach { state =>
      val fullPath = s"$resultPath/${state}"
      val file = new File(fullPath)
      if (file.exists()) {
        throw new IllegalStateException(s"Result path for the state '${fullPath}' already exists! Please, remove it and re-run again!")
      }
      file.mkdir()
      logger.debug(s"Created $fullPath")
    }
    resultPath
  }
}
