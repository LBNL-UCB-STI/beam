package beam.agentsim.agents.ridehail.surgepricing

import beam.agentsim.agents.ridehail.surgepricing.RideHailSurgePricingManager.SurgePriceBin
import beam.sim.BeamServices
import beam.utils.FileUtils
import com.google.inject.Inject
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class FileBasedRideHailSurgePricingManager @Inject()(val beamServices: BeamServices) extends RideHailSurgePricingManager {

  import FileBasedRideHailSurgePricingManager.readSurgeDataFromFile

  override val surgePriceBins: Map[String, ArrayBuffer[SurgePriceBin]] = readSurgeDataFromFile(beamServices.beamConfig.beam.agentsim.agents.rideHail.surgePricing.surgePricingFile)

  override def initializeSurgePricingLevel(surgePriceBin: SurgePriceBin): SurgePriceBin = {
    // Doesn't change
    surgePriceBin
  }

  override def updateSurgePricingLevel(surgePriceBin: RideHailSurgePricingManager.SurgePriceBin): SurgePriceBin = {
    // Doesn't change
    surgePriceBin
  }
}

object FileBasedRideHailSurgePricingManager {
  def readSurgeDataFromFile(filePath: String): Map[String, ArrayBuffer[SurgePriceBin]] = {
    val mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
    val res = mutable.Map[String, ArrayBuffer[SurgePriceBin]]()

    try {
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val tazId = line.get("taz")
        val value = line.get("value").toDouble
        val surgePriceBin = SurgePriceBin(0, 0, value, value)
        val tazArray = res.getOrElseUpdate(tazId,ArrayBuffer[SurgePriceBin]())
        tazArray += surgePriceBin
        res.put(tazId, tazArray)
        line = mapReader.read(header: _*)
      }
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res.toMap
  }
}
