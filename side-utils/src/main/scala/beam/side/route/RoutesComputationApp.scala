package beam.side.route
import java.nio.file.Paths

import beam.side.route.model.{CencusTrack, GHPaths, Trip, Url}
import beam.side.route.processing.data.DataLoaderIO
import beam.side.route.processing.request.GHRequestIO
import beam.side.route.processing.{DataLoader, GHRequest}
import org.http4s.EntityDecoder
import org.http4s.client.Client
import org.http4s.client.blaze._
import zio._
import zio.interop.catz._

import scala.util.{Failure, Try}

case class ComputeConfig(
  cencusTrackPath: String = "",
  odPairsPath: Option[String] = None,
  ghHost: String = "http://localhost:8989",
  output: String = "",
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
  }
}

object RoutesComputationApp extends CatsApp with AppSetup {

  import beam.side.route.model.CencusTrack._
  import beam.side.route.model.GHPaths._
  import org.http4s.circe._
  import zio.console._

  import scala.concurrent.ExecutionContext.global

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {

    type T[+A] = RIO[zio.ZEnv, A]
    implicit val httpClient: ZManaged[zio.ZEnv, Throwable, Client[({ type T[A] = RIO[zio.ZEnv, A] })#T]] =
      BlazeClientBuilder[({ type T[A] = RIO[zio.ZEnv, A] })#T](global).resource.toManagedZIO
    implicit val ghRequest: GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T] = new GHRequestIO(httpClient)
    implicit val pathEncoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths] =
      jsonOf[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths]
    implicit val dataLoader: DataLoader[({ type T[A] = RIO[zio.ZEnv, Queue[A]] })#T, Queue] = DataLoaderIO()

    (for {
      config <- ZIO.fromOption(parser.parse(args, ComputeConfig()))

      cencusQueue <- Queue.bounded[CencusTrack](256)
      promise     <- Promise.make[Exception, Map[String, CencusTrack]]
      _ <- zio.stream.Stream
        .fromQueue[Throwable, CencusTrack](cencusQueue)
        .foldM(Map[String, CencusTrack]())((acc, ct) => IO.effectTotal(acc + (ct.id -> ct)))
        .flatMap(promise.succeed)
        .fork
      loadCencus <- ZManaged
        .make(IO.effectTotal(cencusQueue))(q => q.shutdown)
        .use(
          queue =>
            DataLoader[({ type T[A] = RIO[zio.ZEnv, Queue[A]] })#T, Queue]
              .loadData[CencusTrack](Paths.get(config.cencusTrackPath), queue, false)
        )
        .fork
      _ <- loadCencus.join

      tripQueue <- Queue.bounded[Trip](256)
      _ <- zio.stream.Stream
        .fromQueue[Throwable, Trip](tripQueue)
        .foreach(a => putStrLn(a.toString))
        .fork
      loadTrip <- ZManaged
        .make(IO.effectTotal(tripQueue))(q => q.shutdown)
        .zip(ZManaged.fromEffect(IO.fromOption(config.odPairsPath)))
        .use {
          case (queue, path) =>
            DataLoader[({ type T[A] = RIO[zio.ZEnv, Queue[A]] })#T, Queue]
              .loadData[Trip](Paths.get(path), queue, false)
        }
        .fork

      _ <- loadTrip.join
    } yield config).fold(_ => -1, _ => 0)
  }
}
