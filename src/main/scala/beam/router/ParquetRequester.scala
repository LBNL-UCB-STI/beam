package beam.router

import beam.router.BeamRouter.RoutingRequest
import beam.router.r5.R5Parameters
import beam.sim.BeamHelper
import beam.utils.ParquetReader
import beam.utils.json.AllNeededFormats._
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.util.Utf8

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
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
    parseArgs(args) match {
      case Some(cliOptions) =>
        executeProgram(Array("--config", cliOptions.config.toString), cliOptions.input.toString)
      case None => System.exit(1)
    }
  }

  private def executeProgram(args: Array[String], filePath: String): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val workerParams: R5Parameters = R5Parameters.fromConfig(cfg)
    val requester = new CchRouteRequester(workerParams, new FreeFlowTravelTime)

    val portionSize = 500000
    val counter = new AtomicInteger(0)
    val totalCounter = new AtomicInteger(0)
    var i = 0
    do {
      val requests = getRequests(i * portionSize, portionSize, filePath)
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
        it.slice(drop, drop + take).toArray
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

  case class CliOptions(
    input: Path,
    config: Path
  )

  private def parseArgs(args: Array[String]): Option[CliOptions] = {
    import scopt.OParser
    val builder = OParser.builder[CliOptions]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("parquet-requester"),
        opt[File]('i', "input")
          .required()
          .valueName("<request-file>")
          .action((x, c) => c.copy(input = x.toPath))
          .text("Parquet file containing routing requests"),
        opt[File]('c', "config")
          .required()
          .valueName("<beam-config>")
          .action((x, c) => c.copy(config = x.toPath))
          .text("Beam config"),
        help("help")
      )
    }
    OParser.parse(parser1, args, CliOptions(Paths.get("."), Paths.get(".")))
  }
}
