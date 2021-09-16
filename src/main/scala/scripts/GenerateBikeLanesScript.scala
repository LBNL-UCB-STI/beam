package scripts

import java.io.File
import java.nio.file.Path

import scala.collection.JavaConverters._
import scala.util.Random

import beam.agentsim.infrastructure.geozone.{WgsBoundingBox, WgsCoordinate}
import beam.agentsim.infrastructure.NetworkUtilsExtensions
import beam.sim.common.GeoUtils
import beam.utils.FileUtils
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.Coord
import scopt.OParser

/*
Example of parameters usage:
  --input:test/input/beamville/r5/physsim-network.xml \
  --mode car \
  --output:something.txt \
  --boundBox y1=0.00995,x1=-125.99,x2=17.91,y2=31.5 \
  --epsg 26910 --samplePercentage:80
 */

object GenerateBikeLanesScript extends App {

  case class GenerateBikeLanesParams(
    filePath: Path = null,
    boundingBox: WgsBoundingBox = null,
    mode: String = null,
    samplePercentage: Double = 100d,
    outputFile: Path = null,
    geoUtils: GeoUtils = null
  )

  private val parser = {
    val builder = OParser.builder[GenerateBikeLanesParams]
    import builder._
    OParser.sequence(
      programName("generate-bike-lanes"),
      head("generate-bike-lanes", "0.1"),
      opt[File]("input")
        .required()
        .validate(file =>
          if (file.isFile) success
          else failure(s"$file does not exist")
        )
        .action((x, c) => c.copy(filePath = x.toPath))
        .text("input should be an input file"),
      opt[String]("mode")
        .required()
        .action((v, c) => c.copy(mode = v))
        .text("car, bicycle, etc"),
      opt[String]("epsg")
        .required()
        .validate { value =>
          val validValues = Seq("26910", "4326")
          if (validValues.contains(value)) success
          else failure(s"Illegal epsg parameter [$value]. Valid values: [${validValues.mkString(",")}]")
        }
        .action((epsgCode, c) => c.copy(geoUtils = GeoUtils.fromEpsg(epsgCode))),
      opt[File]("output")
        .required()
        .action((v, c) => c.copy(outputFile = v.toPath))
        .text("output should be the output file"),
      opt[Map[String, Double]]("boundBox")
        .required()
        .validate(map =>
          if (Seq("x1", "y1", "x2", "y2").forall(map.contains)) success
          else failure("missing at leas one parameter of the list: x1,y1,x2,y2")
        )
        .action((allPoints, c) => c.copy(boundingBox = toBoundBox(allPoints)))
        .text("--boundBox x1=10.1,y1=12.5,x2=17.91,y2=31.5"),
      opt[Double]("samplePercentage")
        .validate { value =>
          if (value >= 0 && value <= 100) success
          else failure("samplePercentage should be between 0 and 100")
        }
        .action((value, c) => c.copy(samplePercentage = value))
    )
  }

  OParser.parse(parser, args, GenerateBikeLanesParams()) match {
    case Some(params) =>
      val linkIds = generateBikeLanes(params).map(v => v + System.lineSeparator())
      val outputFile = params.outputFile.toString
      FileUtils.writeToFile(outputFile, linkIds.iterator)
      println(s"***** File [$outputFile] generated with [${linkIds.size}] linkIds!")
    case _ =>
      println("Could not process the parameters")
  }

  def generateBikeLanes(params: GenerateBikeLanesParams): Set[String] = {
    val network = NetworkUtilsExtensions.readNetwork(params.filePath.toString)
    val linkIds = network.getLinks.asScala.collect {
      case (value, link) if linkHasModeAndIsWithinBoundingBox(params, link) => value.toString
    }.toSet
    val itemsToInclude = Math.ceil(linkIds.size * params.samplePercentage / 100).toInt
    Random.shuffle(linkIds).take(itemsToInclude)
  }

  private def linkHasModeAndIsWithinBoundingBox(
    params: GenerateBikeLanesParams,
    link: Link
  ): Boolean = {
    val fromCoordinate = convertToWgs(link.getFromNode.getCoord, params.geoUtils)
    val toCoordinate = convertToWgs(link.getToNode.getCoord, params.geoUtils)
    link.getAllowedModes.asScala.exists(_.equalsIgnoreCase(params.mode)) &&
    params.boundingBox.contains(fromCoordinate) &&
    params.boundingBox.contains(toCoordinate)
  }

  def convertToWgs(coord: Coord, geoUtils: GeoUtils): WgsCoordinate = {
    val coordAsWgs = {
      if (geoUtils eq GeoUtils.GeoUtilsNad83) {
        geoUtils.utm2Wgs(coord)
      } else if (geoUtils eq GeoUtils.GeoUtilsWgs) {
        coord
      } else {
        throw new IllegalArgumentException("GeoUtils is not supported")
      }
    }
    WgsCoordinate(coordAsWgs)
  }

  def toBoundBox(allPoints: Map[String, Double]): WgsBoundingBox = {
    WgsBoundingBox(
      topLeft = WgsCoordinate(latitude = allPoints("y1"), longitude = allPoints("x1")),
      bottomRight = WgsCoordinate(latitude = allPoints("y2"), longitude = allPoints("x2"))
    )
  }
}
