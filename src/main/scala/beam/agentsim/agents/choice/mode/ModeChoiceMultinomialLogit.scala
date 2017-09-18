package beam.agentsim.agents.choice.mode

import java.io.File
import java.util.Random

import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.choice.logit.MulitnomialLogit
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, RIDEHAIL, TRANSIT}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import org.jdom.Document
import org.jdom.Element
import org.jdom.JDOMException
import org.jdom.input.SAXBuilder;
import scala.collection.JavaConverters._


/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(val beamServices: BeamServices) extends ModeChoiceCalculator {

  val model: MulitnomialLogit = parseInputForMNL(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile)

  def parseInputForMNL(modeChoiceParametersFile: String): MulitnomialLogit = {
    val builder: SAXBuilder = new SAXBuilder()
    val document: Document = builder.build(new File(modeChoiceParametersFile)).asInstanceOf[Document]
    var theModelOpt: Option[MulitnomialLogit] = None

    document.getRootElement.getChildren.asScala.foreach{child =>
      if(child.asInstanceOf[Element].getChild("className").getValue.toString.equals("ModeChoiceMultinomialLogit")) {
        val rootNode = child.asInstanceOf[Element].getChild("parameters").asInstanceOf[Element].getChild("multinomialLogit").asInstanceOf[Element]
        theModelOpt = Some(MulitnomialLogit.MulitnomialLogitFactory(rootNode))
      }
    }
    theModelOpt match {
      case Some(theModel) =>
        theModel
      case None =>
        throw new RuntimeException(s"Cannot find a mode choice model of type ModeChoiceMultinomialLogit in file: ${beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile}")
    }
  }

  override def apply(alternatives: Vector[EmbodiedBeamTrip]) = {
    var containsDriveAlt = -1

    var altModesAndTimes: Vector[(BeamMode, Double)] = for (i <- alternatives.indices.toVector) yield {
      val alt = alternatives(i)
      val altMode = if (alt.legs.size == 1) {
        alt.legs.head.beamLeg.mode
      } else {
        if (alt.legs.head.beamLeg.mode.equals(CAR)) {
          containsDriveAlt = i
          CAR
        } else {
          TRANSIT
        }
      }
      val travelTime = (for (leg <- alt.legs) yield leg.beamLeg.duration).foldLeft(0.0) {
        _ + _
      }
      (altMode, travelTime)
    }

    val altUtilities = for (alt <- altModesAndTimes) yield altUtility(alt._1, alt._2)
    val sumExpUtilities = altUtilities.foldLeft(0.0)(_ + math.exp(_))
    val altProbabilities = for (util <- altUtilities) yield math.exp(util) / sumExpUtilities
    val cumulativeAltProbabilities = altProbabilities.scanLeft(0.0)(_ + _)
    //TODO replace with RNG in services
    val randDraw = (new Random()).nextDouble()
    val chosenIndex = for (i <- 1 until cumulativeAltProbabilities.length if randDraw < cumulativeAltProbabilities(i)) yield i - 1
    if (chosenIndex.size > 0) {
      Some(alternatives(chosenIndex.head))
    } else {
      None
    }
  }
  private def altUtility(mode: BeamMode, travelTime: Double): Double = {
    val intercept = if(mode.equals(CAR)){ -3.0 }else{ if(mode.equals(RIDEHAIL)){ -5.0}else{0.0} }
    intercept + -0.001 * travelTime
  }


}
