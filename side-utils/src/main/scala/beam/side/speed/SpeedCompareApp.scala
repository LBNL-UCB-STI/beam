package beam.side.speed

import java.nio.file.Paths

import beam.side.speed.parser.{OsmWays, UberOsmDictionary, UberSpeedRaw}

import scala.util.{Failure, Success, Try}

case class CompareConfig(
  uberSpeedPath: String = "",
  osmMapPath: String = "",
  uberOsmMap: String = "",
  r5MapPath: String = ""
)

trait AppSetup {

  val parser = new scopt.OptionParser[CompareConfig]("speedcompare") {
    head("Uber with BEAM Speed Compare App", "version 1.0")

    opt[String]('u', "uber")
      .required()
      .valueName("<user_path>")
      .action((s, c) => c.copy(uberSpeedPath = s))
      .validate(
        s =>
          Try(Paths.get(s).toFile).filter(_.exists()) match {
            case Success(_) => success
            case Failure(e) => failure(e.getMessage)
        }
      )
      .text("Uber zip path")

    opt[String]('o', "osm")
      .required()
      .valueName("<osm_map>")
      .action((o, c) => c.copy(osmMapPath = o))
      .validate(
        o =>
          Try(Paths.get(o).toFile).filter(_.exists()) match {
            case Success(_) => success
            case Failure(e) => failure(e.getMessage)
        }
      )
      .text("OSM map file to compare")

    opt[String]('d', "dict")
      .required()
      .valueName("<dict_path>")
      .action((d, c) => c.copy(uberOsmMap = d))
      .validate(
        d =>
          Try(Paths.get(d).toFile).filter(_.exists()) match {
            case Success(_) => success
            case Failure(e) => failure(e.getMessage)
        }
      )
      .text("Uber to OSM way dictionary")

    opt[String]('r', "r5")
      .required()
      .valueName("<r5_path>")
      .action((r, c) => c.copy(r5MapPath = r))
      .validate(
        r =>
          Try(Paths.get(r).toFile).filter(_.exists()) match {
            case Success(_) => success
            case Failure(e) => failure(e.getMessage)
        }
      )
      .text("R5 Network")
  }
}

object SpeedCompareApp extends App with AppSetup {

  parser.parse(args, CompareConfig()) match {
    case Some(conf) =>
      //UberSpeedRaw(conf.uberSpeedPath).filterSpeeds.foreach(println)
      UberOsmDictionary(conf.uberOsmMap)
      OsmWays(conf.osmMapPath, conf.r5MapPath).ways.foreach(println)
      System.exit(0)
    case None => System.exit(-1)
  }
}
