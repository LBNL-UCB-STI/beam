package beam.side.route
import java.nio.file.Paths
import java.util.concurrent.Executors

import beam.side.route.model.{GHPaths, TripPath}
import beam.side.route.processing._
import beam.side.route.processing.data.{DataLoaderIO, DataWriterIO, PathComputeIO}
import beam.side.route.processing.request.GHRequestHttpIO
import beam.side.route.processing.tract.{CencusTractDictionaryIO, ODComputeIO}
import cats.effect.Resource
import org.http4s.EntityDecoder
import org.http4s.client.Client
import org.http4s.client.blaze._
import zio._
import zio.interop.catz._

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

case class ComputeConfig(
  cencusTrackPath: String = "",
  odPairsPath: Option[String] = None,
  ghHost: String = "http://localhost:8989",
  output: String = "output.csv",
  cArgs: Map[String, String] = Map()
)

trait AppSetup {

  val parser = new scopt.OptionParser[ComputeConfig]("RouteCompute") {
    head("Census Tract Compute App", "version 1.0")

    opt[String]('c', "cencus")
      .required()
      .valueName("<census_path>")
      .action((s, c) => c.copy(cencusTrackPath = s))
      .validate(
        s =>
          Try(Paths.get(s).toFile).filter(_.exists()) match {
            case Failure(e) => failure(e.getMessage)
            case _          => success
        }
      )
      .text("Census Tract Median path")

    opt[String]("od")
      .valueName("<od_pairs>")
      .action((s, c) => c.copy(odPairsPath = Some(s)))
      .validate(
        s =>
          Try(Paths.get(s).toFile).filter(_.exists()) match {
            case Failure(e) => failure(e.getMessage)
            case _          => success
        }
      )
      .text("O/D pairs path")

    opt[String]('h', "host")
      .valueName("<gh_host>")
      .action((s, c) => c.copy(ghHost = s))
      .validate(
        s =>
          if (s.isEmpty) {
            failure("Empty host name")
          } else {
            success
        }
      )
      .text("O/D pairs path")

    opt[String]('o', "output")
      .valueName("<output_file>")
      .action((s, c) => c.copy(output = s))
      .validate(
        s =>
          if (s.isEmpty) {
            failure("Empty output file")
          } else {
            success
        }
      )
      .text("Output file")
  }
}

object RoutesComputationApp extends CatsApp with AppSetup {

  import TripPath._
  import beam.side.route.model.GHPaths._
  import org.http4s.circe._
  import runtime._

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {

    type T[+A] = RIO[zio.ZEnv, A]
    implicit val httpClient
      : Resource[({ type T[A] = RIO[zio.ZEnv, A] })#T, Client[({ type T[A] = RIO[zio.ZEnv, A] })#T]] =
      BlazeClientBuilder[({ type T[A] = RIO[zio.ZEnv, A] })#T](
        ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))
      ).resource
    implicit val ghRequest: GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T] = new GHRequestHttpIO(httpClient)
    implicit val pathEncoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths] =
      jsonOf[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths]
    implicit val dataLoader: DataLoader[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] = DataLoaderIO()
    implicit val cencusDictionary: CencusTractDictionary[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] =
      CencusTractDictionaryIO()
    implicit val odCompute: ODCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T] = ODComputeIO()
    implicit val dataWriter: DataWriter[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] = DataWriterIO()

    (for {
      config <- ZIO.fromOption(parser.parse(args, ComputeConfig()))

      promise <- CencusTractDictionary[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue].compose(config.cencusTrackPath)

      pathCompute: PathCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T] = PathComputeIO(config.ghHost)

      pathQueue <- ODCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T]
        .pairTrip(config.odPairsPath, promise)(pathCompute, pathEncoder, ghRequest, dataLoader)

      linesFork <- DataWriter[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue]
        .writeFile(Paths.get(config.output), pathQueue)
        .fork

      _ <- linesFork.join
    } yield config).fold(_ => -1, _ => 0)
  }
}
