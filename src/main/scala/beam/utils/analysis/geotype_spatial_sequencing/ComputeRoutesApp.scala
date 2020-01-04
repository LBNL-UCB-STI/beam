package beam.utils.analysis.geotype_spatial_sequencing

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import com.google.common.io.Files
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

object ComputeRoutesApp extends LazyLogging {
  implicit val ex: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  def main(args: Array[String]): Unit = {
    assert(args.size == 4)
    val pathToCencusTrack = args(0)
    val pathToOd = args(1)
    val pathToGh = args(2)
    val outputPath = args(3)

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

    val toProcess = trips
    val numberOfRequest: Int = toProcess.length
    val totalProcessed: AtomicInteger = new AtomicInteger(0)

    val nThreads = Runtime.getRuntime.availableProcessors
    val perThreadPortion = toProcess.length / nThreads
    logger.info(s"nThreads: $nThreads, perThreadPortion: $perThreadPortion")
    val groupedPerThread = toProcess.grouped(perThreadPortion).zipWithIndex

    val onePctNumber = (numberOfRequest * 0.01).toInt
    val s = System.currentTimeMillis()
    val futures = groupedPerThread.map {
      case (trips, groupIndex) =>
        Future {
          val fileName = Files.getNameWithoutExtension(outputPath)
          val fileExt = Files.getFileExtension(outputPath)
          val folder = new File(outputPath).toPath.getParent.toString
          val fullOutputPathWithPartitionId = s"$folder/${fileName}_${groupIndex}.$fileExt"
          logger.info(s"For the partition $groupIndex the output file path is $fullOutputPathWithPartitionId")
          val csvWriter = new CsvWriter(fullOutputPathWithPartitionId, Vector("source", "destination", "linestring"))
          try {
            trips.foreach { trip =>
              val maybeRoute = calcRoute(routeResolver, censusTrackMap, trip)
              maybeRoute.foreach { route =>
                val escapedLineString = "\"" + route.lineString.toString + "\""
                csvWriter.write(route.origin.id, route.dest.id, escapedLineString)
                totalProcessed.getAndIncrement()
              }
              val processed = totalProcessed.get()
              if (processed % onePctNumber == 0) {
                val pct = 100 * processed.toDouble / numberOfRequest
                val tookSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - s)
                val msg = s"Processed $processed out of $numberOfRequest => $pct % in $tookSeconds seconds"
                println(msg)
                logger.info(msg)
              }
            }
          } finally { Try(csvWriter.close()) }
        }
    }.toList
    Await.result(Future.sequence(futures), Duration.Inf)
    logger.info("Done")
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
        Some(OutputResult(originCensusTrack.get, destCensusTrack.get, pointAsLineString))
      }
    } else None
  }
}
