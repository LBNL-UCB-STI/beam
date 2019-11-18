package beam.side.route
import java.nio.file.Paths

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
      .text("Census Tract Median path")
  }
}

object RoutesComputationApp extends App {}
