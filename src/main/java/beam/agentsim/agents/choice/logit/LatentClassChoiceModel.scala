package beam.agentsim.agents.choice.logit

import java.io.File

import beam.sim.{BeamServices, HasServices}

import scala.xml.XML
import purecsv.unsafe._

/**
  * BEAM
  */
class LatentClassChoiceModel(override val beamServices: BeamServices) extends HasServices with Cloneable {

  val lccmData = parseModeChoiceParams(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile)

  val classMembershipModel: MulitnomialLogit = extractClassMembershipParams(lccmData)

  def parseModeChoiceParams(modeChoiceParamsFilePath: String): Vector[LccmData] = {
    val params = XML.loadFile(modeChoiceParamsFilePath)
    val paramsFile = s"${beamServices.beamConfig.beam.sharedInputs}/${(params \\ "modeChoices" \\ "lccm" \\ "parameters").text}"
    CSVReader[LccmData].readCSVFromFile(new File(paramsFile),skipHeader=true).toVector
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

