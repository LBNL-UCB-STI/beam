package matsim.conversion

import java.io.FileWriter
import java.util

import beam.agentsim.infrastructure.TAZTreeMap
import beam.utils.scripts.HasXY.wgs2Utm
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.supercsv.io.{CsvMapWriter, ICsvMapWriter}
import org.supercsv.prefs.CsvPreference

object SiouxFallsConversion extends App {
  generateTazDefaults(ConversionConfig.getConfig)

  def generateTazDefaults(conversionConfig: ConversionConfig) = {
    val outputFilePath = conversionConfig.outputDirectory + "/taz-centers.csv"
    if(conversionConfig.shapeFile.isDefined && conversionConfig.tazIDFieldName.isDefined){
      TAZTreeMap.shapeFileToCsv(conversionConfig.shapeFile.get, conversionConfig.tazIDFieldName.get, outputFilePath)
    }else {
      val defaultTaz = getDefaultTaz()
      generateSingleDefaultTaz(defaultTaz, outputFilePath)
    }
  }

  def generateSingleDefaultTaz(default: TAZTreeMap.CsvTaz, outputFilePath: String) = {
    var mapWriter: ICsvMapWriter = null
    try {
      mapWriter =
        new CsvMapWriter(new FileWriter(outputFilePath), CsvPreference.STANDARD_PREFERENCE)

      val processors = TAZTreeMap.getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y")

      mapWriter.writeHeader(header: _*)

      val tazToWrite = new util.HashMap[String, Object]()
      tazToWrite.put(header(0), default.id)

      val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation("utm", "EPSG:26910")
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

  def getDefaultTaz(): TAZTreeMap.CsvTaz = {
    //TODO create where center is the midpoint of the overall extent of the region (as defined by the bounding box around the Network).
    TAZTreeMap.CsvTaz("1", 0.0, 0.0)
  }



}
