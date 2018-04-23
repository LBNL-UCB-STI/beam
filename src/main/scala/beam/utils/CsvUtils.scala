package beam.utils

import java.io._
import java.util.HashMap
import java.util.zip.GZIPInputStream

import beam.agentsim.infrastructure.ParkingStall._
import beam.agentsim.infrastructure.{ParkingStall, TAZ}
import org.matsim.api.core.v01.Id
import org.supercsv.cellprocessor.constraint.{NotNull, UniqueHashCode}
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io.{CsvMapReader, CsvMapWriter, ICsvMapReader, ICsvMapWriter}
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable

object CsvUtils {

  def readCsvFile(filePath: String): mutable.Map[StallAttributes, StallValues] = {
    var mapReader: ICsvMapReader = null
    val res: mutable.Map[StallAttributes, StallValues] = mutable.Map()
    try{
      mapReader = new CsvMapReader(readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header:_*)
      while(null != line){

        val taz = Id.create((line.get("taz")).toUpperCase, classOf[TAZ])
        val parkingType = ParkingType.fromString(line.get("parkingType"))
        val pricingModel = PricingModel.fromString(line.get("pricingModel"))
        val chargingType = ChargingType.fromString(line.get("chargingType"))
        val numStalls = line.get("numStalls").toInt
//        val parkingId = line.get("parkingId")
        val fee = line.get("fee").toDouble

        res.put(StallAttributes(taz, parkingType, pricingModel, chargingType), StallValues(numStalls, fee))

        line = mapReader.read(header:_*)
      }

    } finally{
      if(null != mapReader)
        mapReader.close()
    }
    res
  }

  private def readerFromFile(filePath: String): java.io.Reader  = {
    if(filePath.endsWith(".gz")){
      new InputStreamReader(new GZIPInputStream(new BufferedInputStream(new FileInputStream(filePath))))
    } else {
      new FileReader(filePath)
    }
  }

  def parkingStallToCsv(pooledResources: mutable.Map[ParkingStall.StallAttributes, StallValues], writeDestinationPath: String): Unit = {
    var mapWriter: ICsvMapWriter   = null;
    try {
      mapWriter = new CsvMapWriter(new FileWriter(writeDestinationPath),
        CsvPreference.STANDARD_PREFERENCE);

      val processors = getProcessors
      val header = Array[String]("taz", "parkingType", "pricingModel", "chargingType", "numStalls", "fee")//, "parkingId"
      mapWriter.writeHeader(header:_*)

      val range = (1 to pooledResources.size)
      val resourcesWithId = (pooledResources zip range)
        .toSeq
        .sortBy(_._2)

      for(((attrs, values), id) <- resourcesWithId){
        val tazToWrite = new HashMap[String, Object]();
        tazToWrite.put(header(0), attrs.tazId)
        tazToWrite.put(header(1), attrs.parkingType.toString)
        tazToWrite.put(header(2), attrs.pricingModel.toString)
        tazToWrite.put(header(3), attrs.chargingType.toString)
        tazToWrite.put(header(4), "" + values.stall)
        tazToWrite.put(header(5), "" + values.fee)
//        tazToWrite.put(header(6), "" + values.parkingId.getOrElse(Id.create(id, classOf[StallValues])))
        mapWriter.write(tazToWrite, header, processors)
      }
    } finally {
      if( mapWriter != null ) {
        mapWriter.close()
      }
    }
  }

  def getHash(concatParams: Any*): Int = {
    val concatString = concatParams.foldLeft("")((a, b) => a + b)
    concatString.hashCode
  }



  private def getProcessors: Array[CellProcessor]  = {
    Array[CellProcessor](
      new NotNull(), // Id (must be unique)
      new NotNull(),
      new NotNull(),
      new NotNull(),
      new NotNull(),
      new NotNull()) //new UniqueHashCode()

  }

}
