package beam.utils.matsim_conversion

import java.io.{File, FileWriter}
import java.nio.file.Paths
import java.util

import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.supercsv.io.{CsvMapWriter, ICsvMapWriter}
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._

object MatsimConversionTool extends App {

  val dummyGtfsPath = "test/input/beamville/r5/dummy.zip"

  if (null != args && args.size > 0) {
    val beamConfigFilePath = args(0) //"test/input/beamville/beam.conf"

    val config = parseFileSubstitutingInputDirectory(beamConfigFilePath)
    val conversionConfig = ConversionConfig(config)

    val network = NetworkUtils.createNetwork()
//    println(s"Network file ${conversionConfig.matsimNetworkFile}")
    new MatsimNetworkReader(network).readFile(conversionConfig.matsimNetworkFile)

    MatsimPlanConversion.generateScenarioData(conversionConfig)
    generateTazDefaults(conversionConfig, network)
    generateOsmFilteringCommand(conversionConfig, network)

    val r5OutputFolder = conversionConfig.scenarioDirectory + "/r5"
    val dummyGtfsOut = r5OutputFolder + "/dummy.zip"
    FileUtils.copyFile(new File(dummyGtfsPath), new File(dummyGtfsOut))
  } else {
    println("Please specify config/file/path parameter")
  }

  def generateOsmFilteringCommand(cf: ConversionConfig, network: Network): Unit = {
    val boundingBox =
      ConversionConfig.getBoundingBoxConfig(network, cf.localCRS, cf.boundingBoxBuffer)
    val outputFile = s"${cf.scenarioDirectory}/r5/${cf.scenarioName}.osm.pbf"
    val commandOut =
      s"""
         osmosis --read-pbf file=${cf.osmFile} --bounding-box top=${boundingBox.top} left=${boundingBox.left} bottom=${boundingBox.bottom} right=${boundingBox.right} completeWays=yes completeRelations=yes clipIncompleteEntities=true --write-pbf file=$outputFile
      """.stripMargin

    println(s"Run following format to clip open street data file to network boundaries if required")
    println(commandOut)
  }

  def generateTazDefaults(conversionConfig: ConversionConfig, network: Network): Unit = {
    val outputFilePath = s"${conversionConfig.scenarioDirectory}/taz-centers.csv"

    if (conversionConfig.shapeConfig.isDefined) {
      val shapeConfig = conversionConfig.shapeConfig.get
      ShapeUtils.shapeFileToCsv(shapeConfig.shapeFile, shapeConfig.tazIDFieldName, outputFilePath)
    } else {
      val defaultTaz = getDefaultTaz(network, conversionConfig.localCRS)
      generateSingleDefaultTaz(defaultTaz, outputFilePath, conversionConfig.localCRS)
    }
  }

  def generateSingleDefaultTaz(
    default: ShapeUtils.CsvTaz,
    outputFilePath: String,
    localCRS: String
  ): Unit = {
    var mapWriter: ICsvMapWriter = null
    try {
      mapWriter = new CsvMapWriter(new FileWriter(outputFilePath), CsvPreference.STANDARD_PREFERENCE)

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
    val boundingBox = ConversionConfig.getBoundingBoxConfig(network, localCRS)
    val minX = boundingBox.left
    val maxX = boundingBox.right
    val minY = boundingBox.bottom
    val maxY = boundingBox.top

    val midX = (maxX + minX) / 2
    val midY = (maxY + minY) / 2

    ShapeUtils.CsvTaz("1", midX, midY, 1)
  }

  def parseFileSubstitutingInputDirectory(fileName: String): com.typesafe.config.Config = {
    val file = Paths.get(fileName).toFile
    parseFileSubstitutingInputDirectory(file)
  }

  def parseFileSubstitutingInputDirectory(file: File): com.typesafe.config.Config = {
    ConfigFactory
      .parseFile(file)
      .withFallback(
        ConfigFactory.parseMap(Map("beam.inputDirectory" -> file.getAbsoluteFile.getParent).asJava)
      )
      .resolve
  }

}
