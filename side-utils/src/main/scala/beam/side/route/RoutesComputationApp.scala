package beam.side.route
import java.nio.file.Paths

import beam.side.route.model.{Url, Way}
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

  import Way._
  import org.http4s.circe._
  import zio.console._

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {

    implicit val httpClient: Task[Client[Task]] = Http1Client[Task]()
    implicit val ghRequest: GHRequest[Task] = new GHRequestIO(httpClient)
    implicit val wayEncoder: EntityDecoder[Task, Way] = jsonOf[Task, Way]

    (for {
      config <- ZIO.fromOption(parser.parse(args, ComputeConfig()))
      url = Url(
        "http://localhost:8989",
        "route",
        Seq(
          "point"          -> Seq(40.748484, -73.62882),
          "point"          -> Seq(40.751282, -73.611691),
          "vehicle"        -> "car",
          "points_encoded" -> false,
          "type"           -> "json"
        )
      )
      forkRequest <- GHRequest[Task].request[Way](url).fork
      response    <- forkRequest.join
      _           <- putStrLn(response.toString)
    } yield config).fold(_ => -1, _ => 0)
  }
}
