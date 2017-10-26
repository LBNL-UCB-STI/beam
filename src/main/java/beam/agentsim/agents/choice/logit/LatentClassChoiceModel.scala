package beam.agentsim.agents.choice.logit

import java.io.File

import beam.sim.{BeamServices, HasServices}
import org.matsim.core.utils.io.IOUtils

import scala.xml.XML
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference;

/**
  * BEAM
  */
class LatentClassChoiceModel(override val beamServices: BeamServices) extends HasServices with Cloneable {

  val lccmData = parseModeChoiceParams(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile)

  val classMembershipModel: MulitnomialLogit = extractClassMembershipParams(lccmData)

  def parseModeChoiceParams(modeChoiceParamsFilePath: String): Vector[LccmData] = {
    val params = XML.loadFile(modeChoiceParamsFilePath)
    val paramsFile = s"${beamServices.beamConfig.beam.sharedInputs}/${(params \\ "modeChoices" \\ "lccm" \\ "parameters").text}"
    val csvData = (new CsvMapReader(IOUtils.getBufferedReader(paramsFile), CsvPreference.STANDARD_PREFERENCE)).read("???")
    Vector()
  }

  def extractClassMembershipParams(lccmData: Vector[LccmData]): MulitnomialLogit = {
    MulitnomialLogit.MulitnomialLogitFactory("")
  }

  //TODO actually clone this
  override def clone():LatentClassChoiceModel = {
    new LatentClassChoiceModel(beamServices)
  }
}

case class LccmData(model: String, tourType: String, variable: String, alternative: String, units: String, latentClass: String, value: Double)

