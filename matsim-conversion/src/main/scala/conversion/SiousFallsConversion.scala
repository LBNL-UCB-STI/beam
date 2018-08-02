package conversion

import java.io.{File, FileWriter}
import java.nio.file.Paths
import java.util

import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.supercsv.io.{CsvMapWriter, ICsvMapWriter}
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._

object SiouxFallsConversion extends App {

  val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation("EPSG:4326", "EPSG:26910")
  val beamConfigFilePath = "test/input/beamville/beam.conf"

  val config = parseFileSubstitutingInputDirectory(beamConfigFilePath)

  generateTazDefaults(ConversionConfig(config))

  def generateTazDefaults(conversionConfig: ConversionConfig) = {
    val outputFilePath = conversionConfig.outputDirectory + "/taz-centers.csv"

    if(conversionConfig.shapeConfig.isDefined){
      val shapeConfig = conversionConfig.shapeConfig.get
      ShapeUtils.shapeFileToCsv(shapeConfig.shapeFile, shapeConfig.tazIDFieldName, outputFilePath, conversionConfig.localCRS)
    }else {
      val defaultTaz = getDefaultTaz()
      generateSingleDefaultTaz(defaultTaz, outputFilePath)
    }
  }

  def generateSingleDefaultTaz(default: ShapeUtils.CsvTaz, outputFilePath: String) = {
    var mapWriter: ICsvMapWriter = null
    try {
      mapWriter =
        new CsvMapWriter(new FileWriter(outputFilePath), CsvPreference.STANDARD_PREFERENCE)

      val processors = ShapeUtils.getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y")

      mapWriter.writeHeader(header: _*)

      val tazToWrite = new util.HashMap[String, Object]()
      tazToWrite.put(header(0), default.id)

      val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation("epsg:32631", "EPSG:4326")
      val transformedCoord: Coord = wgs2Utm.transform(new Coord(default.coordX, default.coordY))
      val tcoord = utm2Wgs.transform(new Coord(transformedCoord.getX, transformedCoord.getY))

      tazToWrite.put(header(1), tcoord.getX.toString)
      tazToWrite.put(header(2), tcoord.getY.toString)
      mapWriter.write(tazToWrite, header, processors)
    } finally {
      if (mapWriter != null) {
        mapWriter.close()
      }
    }
  }

  def getDefaultTaz(): ShapeUtils.CsvTaz = {
    //TODO create where center is the midpoint of the overall extent of the region (as defined by the bounding box around the Network).
    ShapeUtils.CsvTaz("1", 0.0, 0.0)
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
