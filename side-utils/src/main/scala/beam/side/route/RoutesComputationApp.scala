package beam.side.route
import java.nio.file.Paths

import beam.side.route.model.{GHPaths, Url, Way}
import beam.side.route.processing.GHRequest
import beam.side.route.processing.request.GHRequestIO
import org.http4s.EntityDecoder
import org.http4s.client.Client
import org.http4s.client.blaze._
import zio._
import zio.interop.catz._

import scala.util.{Failure, Try}

case class ComputeConfig(
  cencusTrackPath: String = "",
  odPairsPath: Option[String] = None,
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
  }
}

object RoutesComputationApp extends CatsApp with AppSetup {

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

    (for {
      config <- ZIO.fromOption(parser.parse(args, ComputeConfig()))
      url = Url(
        "http://localhost:8989",
        "route",
        Seq(
          "point"          -> Seq((40.748484, -73.62882), (40.751282, -73.611691)),
          "vehicle"        -> "car",
          "points_encoded" -> false,
          "type"           -> "json",
          "calc_points"    -> true
        )
      )
      forkRequest <- GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T].request[GHPaths](url).fork
      response    <- forkRequest.join
      _           <- putStrLn(response.toString)
    } yield config).fold(_ => -1, _ => 0)
  }
}
