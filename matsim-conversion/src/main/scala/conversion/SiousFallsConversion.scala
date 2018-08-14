package conversion

import java.io.{File, FileWriter}
import java.nio.file.Paths
import java.util

import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.supercsv.io.{CsvMapWriter, ICsvMapWriter}
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._

object SiouxFallsConversion extends App {

  val beamConfigFilePath = "test/input/beamville/beam.conf"

  val config = parseFileSubstitutingInputDirectory(beamConfigFilePath)
  val conversionConfig = ConversionConfig(config)

  val network = NetworkUtils.createNetwork()
  new MatsimNetworkReader(network).readFile(conversionConfig.matsimNetworkFile)


  MatsimPlanConversion.generateSiouxFallsXml(conversionConfig)
  generateTazDefaults(ConversionConfig(config), network)
  generateOsmFilteringCommand(OSMFilteringConfig(config, network))

  def generateOsmFilteringCommand(ofc: OSMFilteringConfig) = {
    val commandOut =
      s"""
         osmosis --read-pbf file=${ofc.pbfFile} --bounding-box top=${ofc.boundingBox.top} left=${ofc.boundingBox.left} bottom=${ofc.boundingBox.bottom} right=${ofc.boundingBox.right} completeWays=yes completeRelations=yes clipIncompleteEntities=true --write-pbf file=${ofc.outputFile}
      """.stripMargin

    println(commandOut)
  }


  def generateTazDefaults(conversionConfig: ConversionConfig, network: Network) = {
    val outputFilePath = conversionConfig.outputDirectory + "/taz-centers.csv"

    if(conversionConfig.shapeConfig.isDefined){
      val shapeConfig = conversionConfig.shapeConfig.get
      ShapeUtils.shapeFileToCsv(shapeConfig.shapeFile, shapeConfig.tazIDFieldName, outputFilePath, conversionConfig.localCRS)
    }else {
      val defaultTaz = getDefaultTaz(network, conversionConfig.localCRS)
      generateSingleDefaultTaz(defaultTaz, outputFilePath, conversionConfig.localCRS)
    }
  }

  def generateSingleDefaultTaz(default: ShapeUtils.CsvTaz, outputFilePath: String, localCRS: String) = {
    var mapWriter: ICsvMapWriter = null
    try {
      mapWriter =
        new CsvMapWriter(new FileWriter(outputFilePath), CsvPreference.STANDARD_PREFERENCE)

      val processors = ShapeUtils.getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y")

      mapWriter.writeHeader(header: _*)

      val tazToWrite = new util.HashMap[String, Object]()
      tazToWrite.put(header(0), default.id)

      val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation("EPSG:4326", localCRS)
      val transformedCoord: Coord = wgs2Utm.transform(new Coord(default.coordX, default.coordY))
      val tcoord = wgs2Utm.transform(new Coord(transformedCoord.getX, transformedCoord.getY))

      tazToWrite.put(header(1), tcoord.getX.toString)
      tazToWrite.put(header(2), tcoord.getY.toString)
      mapWriter.write(tazToWrite, header, processors)
    } finally {
      if (mapWriter != null) {
        mapWriter.close()
      }
    }
  }

  def getDefaultTaz(network: Network, localCRS: String): ShapeUtils.CsvTaz = {
    val boundingBox = OSMFilteringConfig.getBoundingBoxConfig(network, localCRS)
    val minX = boundingBox.left
    val maxX = boundingBox.right
    val minY = boundingBox.bottom
    val maxY = boundingBox.top

    val midX = (maxX + minX) / 2
    val midY = (maxY + minY) / 2

    ShapeUtils.CsvTaz("1", midX, midY)
  }

  def parseFileSubstitutingInputDirectory(fileName: String): com.typesafe.config.Config = {
    val file = Paths.get(fileName).toFile
    parseFileSubstitutingInputDirectory(file)
  }

  def parseFileSubstitutingInputDirectory(file: File): com.typesafe.config.Config = {
    ConfigFactory.parseFile(file)
      .withFallback(ConfigFactory.parseMap(Map("beam.inputDirectory" -> file.getAbsoluteFile.getParent).asJava))
      .resolve
  }

}
