package beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit
import beam.sim.{BeamServices, HasServices}

import scala.xml.XML
import kantan.csv._
import kantan.csv.ops._

/**
  * BEAM
  */
class LatentClassChoiceModel(override val beamServices: BeamServices) extends HasServices with Cloneable {

  val lccmData = parseModeChoiceParams(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile)

  val classMembershipModel: MulitnomialLogit = extractClassMembershipParams(lccmData)

  def parseModeChoiceParams(modeChoiceParamsFilePath: String): Vector[LccmData] = {
    val params = XML.loadFile(modeChoiceParamsFilePath)
    val paramsFile = s"${beamServices.beamConfig.beam.sharedInputs}/${(params \\ "modeChoices" \\ "lccm" \\ "parameters").text}"

    val rawData: java.net.URL = getClass.getResource(paramsFile)
    val reader = rawData.readCsv[List, LccmData](rfc.withHeader)

    reader.map(_.get).toVector
  }

  def extractClassMembershipParams(lccmData: Vector[LccmData]): MulitnomialLogit = {

  }

  //TODO actually clone this
  override def clone():LatentClassChoiceModel = {
    new LatentClassChoiceModel(beamServices)
  }
}

case class LccmData(model: String, tourType: String, variable: String, alternative: String, units: String, latentClass: String, value: Either[Double, Option[String]])

