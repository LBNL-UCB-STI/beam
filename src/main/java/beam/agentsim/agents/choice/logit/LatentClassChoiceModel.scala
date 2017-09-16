package beam.agentsim.agents.choice.logit

import beam.sim.{BeamServices, HasServices}

import scala.io.Source
import scala.xml.XML

/**
  * BEAM
  */
class LatentClassChoiceModel(override val beamServices: BeamServices) extends HasServices {

  val lccmData = parseModeChoiceParams(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile)


  def parseModeChoiceParams(modeChoiceParamsFilePath: String): LccmData = {
    val params = XML.loadFile(modeChoiceParamsFilePath)
    val paramsFile = params \\ "modeChoices" \\ "lccm" \\ "parameters"

    val content = Source.fromFile(s"${beamServices.beamConfig.beam.sharedInputs}/${paramsFile.text}").getLines.map(_.split(",").toVector)

    val headerData = content.next
    val stringData = content.map(headerData.zip(_).toMap)

    for {
      line <- Source.fromFile(fileName).getLines().drop(1).toVector
      values = line.split(",").map(_.trim)
    } yield Sale(values(0), values(1), values(2).toInt, values(3), values(4))
  }

//    stringData.filter()
    LccmData(headerData, Map(), Map())
  }
}

case class LccmData(header: Vector[String], tableCategorical: Map[String,Vector[String]], tableNumeric: Map[String,Vector[Double]])

