package beam.router

import beam.router.BeamRouter.RoutingRequest
import beam.router.r5.R5Parameters
import beam.sim.BeamHelper
import beam.utils.ParquetReader
import beam.utils.json.AllNeededFormats._
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.util.Utf8

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object ParquetRequester extends BeamHelper with LazyLogging {

  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    16,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("requester-%d").build()
  )
  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

  private val routeTime = new AtomicLong(0)

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val workerParams: R5Parameters = R5Parameters.fromConfig(cfg)
    val requester = new CchRouteRequester(workerParams, new FreeFlowTravelTime)

    val portionSize = 500000
    val counter = new AtomicInteger(0)
    val totalCounter = new AtomicInteger(0)
    var i = 0
    do {
      val requests = getRequests(i * portionSize, portionSize, "/home/crixal/Downloads/0.routingRequest.parquet")
      counter.set(requests.length)
      if (counter.get() != 0) {
        requests
          .grouped(128)
          .foreach { reqList =>
            val futures = reqList.map { req =>
              Future {
                val start = System.currentTimeMillis()
                requester.route(req)
                routeTime.addAndGet(System.currentTimeMillis() - start)
              }
            }.toList

            Await.result(Future.sequence(futures), 10.minutes)
          }
      }

      totalCounter.addAndGet(counter.get())
      i += 1
    } while (counter.get() != 0)

    logger.info(s"Total requests: ${totalCounter.get()}")
    logger.info(s"Total route time: ${routeTime.get()}")
  }

  private def getRequests(drop: Int, take: Int, filePath: String): Array[RoutingRequest] = {
    val requestRecords = {
      val (it, toClose) = ParquetReader.read(filePath)
      try {
        it.drop(drop).take(take).toArray
      } finally {
        toClose.close()
      }
    }

    val requests = requestRecords.map { req =>
      val reqJsonStr = new String(req.get("requestAsJson").asInstanceOf[Utf8].getBytes, StandardCharsets.UTF_8)
      io.circe.parser.parse(reqJsonStr).right.get.as[RoutingRequest].right.get
    }
    logger.info(s"requests: ${requests.length}")
    requests
  }
}
