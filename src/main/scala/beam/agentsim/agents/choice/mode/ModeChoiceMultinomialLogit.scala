package beam.agentsim.agents.choice.mode

import java.io.File
import java.util
import java.util.{LinkedHashMap, Random}

import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.choice.logit.MulitnomialLogit
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, DRIVE_TRANSIT, RIDEHAIL, TRANSIT, WALK_TRANSIT}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import org.jdom.Document
import org.jdom.Element
import org.jdom.input.SAXBuilder
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._


/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(val beamServices: BeamServices, val model: MultinomialLogit ) extends ModeChoiceCalculator {

  var expectedMaximumUtility: Double = 0.0

  override def clone(): ModeChoiceCalculator = {
    val  mnl: MultinomialLogit = this.model.clone()
    new ModeChoiceMultinomialLogit(beamServices,mnl)
  }

  override def apply(alternatives: Vector[EmbodiedBeamTrip], choiceAttributes: Option[AttributesOfIndividual]) = {
    alternatives.isEmpty match {
      case true =>
        None
      case false =>

        val inputData: util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]] = new util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]]()

        val transitFareDefaults: Vector[BigDecimal] = TransitFareDefaults.estimateTransitFares(alternatives)
        val gasolineCostDefaults: Vector[BigDecimal] = DrivingCostDefaults.estimateDrivingCost(alternatives, beamServices)
        val bridgeTollsDefaults: Vector[BigDecimal] = BridgeTollDefaults.estimateBrdigeFares(alternatives, beamServices)

        if (bridgeTollsDefaults.map(_.toDouble).sum > 0) {
          val i = 0
        }

        val modeCostTimeTransfers = alternatives.zipWithIndex.map { altAndIdx =>
          val totalCost = altAndIdx._1.tripClassifier match {
            case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
              (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2))*beamServices.beamConfig.beam.agentsim.tuning.transitPrice + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2)
            case RIDEHAIL =>
              altAndIdx._1.costEstimate*beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice + bridgeTollsDefaults(altAndIdx._2)*beamServices.beamConfig.beam.agentsim.tuning.tollPrice
            case CAR =>
              altAndIdx._1.costEstimate + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2)*beamServices.beamConfig.beam.agentsim.tuning.tollPrice
            case _ =>
              altAndIdx._1.costEstimate
          }
          val numTransfers = altAndIdx._1.tripClassifier match {
            case TRANSIT =>
              var nVeh = -1
              var vehId = Id.create("dummy",classOf[Vehicle])
              altAndIdx._1.legs.foreach{ leg =>
                if(leg.beamLeg.mode.isTransit() && leg.beamVehicleId != vehId){
                  vehId = leg.beamVehicleId
                  nVeh = nVeh + 1
                }
              }
              nVeh
            case _ =>
              0
          }
          assert(numTransfers>=0)
          ModeCostTimeTransfer(altAndIdx._1.tripClassifier, totalCost, altAndIdx._1.totalTravelTime, numTransfers, altAndIdx._2)
        }
        val groupedByMode = (modeCostTimeTransfers ++ ModeChoiceMultinomialLogit.defaultAlternatives).sortBy(_.mode.value).groupBy(_.mode)

        val bestInGroup = groupedByMode.map { case (mode, modeCostTimeSegment) =>
          // Which dominates at $18/hr
          modeCostTimeSegment.map { mct => (mct.time / 3600 * 18 + mct.cost.toDouble, mct) }.sortBy(_._1).head._2
        }

        bestInGroup.foreach { mct =>
          val altData: util.LinkedHashMap[java.lang.String, java.lang.Double] = new util.LinkedHashMap[java.lang.String, java.lang.Double]()
          altData.put("cost", mct.cost.toDouble)
          altData.put("time", mct.time)
          if(mct.mode == TRANSIT){
            altData.put("transfer",mct.numTransfers.toDouble)
          }
          inputData.put(mct.mode.value, altData)
        }

        val chosenMode = model.makeRandomChoice(inputData, new Random())
        expectedMaximumUtility = model.getExpectedMaximumUtility
        model.clear()
        val chosenModeCostTime = bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode))

        chosenModeCostTime.isEmpty match {
          case true =>
            None
          case false if chosenModeCostTime.head.index == -1 || chosenModeCostTime.head.index >= alternatives.size =>
            None
          case false =>
            Some(alternatives(chosenModeCostTime.head.index))
        }
    }
  }

  //    val altUtilities = for (alt <- altModesAndTimes) yield altUtility(alt._1, alt._2)
//    val sumExpUtilities = altUtilities.foldLeft(0.0)(_ + math.exp(_))
//    val altProbabilities = for (util <- altUtilities) yield math.exp(util) / sumExpUtilities
//    val cumulativeAltProbabilities = altProbabilities.scanLeft(0.0)(_ + _)
//    //TODO replace with RNG in services
//    val randDraw = (new Random()).nextDouble()
//    val chosenIndex = for (i <- 1 until cumulativeAltProbabilities.length if randDraw < cumulativeAltProbabilities(i)) yield i - 1
//    if (chosenIndex.size > 0) {
//      Some(alternatives(chosenIndex.head))
//    } else {
//      None
//    }
//  private def altUtility(mode: BeamMode, travelTime: Double): Double = {
//    val intercept = if(mode.equals(CAR)){ -3.0 }else{ if(mode.equals(RIDEHAIL)){ -5.0}else{0.0} }
//    intercept + -0.001 * travelTime
//  }

}

object ModeChoiceMultinomialLogit {
  case class ModeCostTimeTransfer(mode: BeamMode, cost: BigDecimal, time: Double, numTransfers: Int, index: Int = -1)

  val defaultAlternatives = Vector(
    ModeCostTimeTransfer(BeamMode.WALK,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.CAR,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.RIDEHAIL,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.BIKE,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.TRANSIT,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue)
  )

  def apply(beamServices: BeamServices): ModeChoiceMultinomialLogit = {
    new ModeChoiceMultinomialLogit(beamServices,ModeChoiceMultinomialLogit.parseInputForMNL(beamServices))
  }

  def parseInputForMNL(beamServices: BeamServices): MultinomialLogit = {
    val modeChoiceParametersFile = beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile
    val builder: SAXBuilder = new SAXBuilder()
    val document: Document = builder.build(new File(modeChoiceParametersFile)).asInstanceOf[Document]
    var theModelOpt: Option[MultinomialLogit] = None

    document.getRootElement.getChildren.asScala.foreach{child =>
      if(child.asInstanceOf[Element].getName.equalsIgnoreCase("mnl")){
        val rootNode = child.asInstanceOf[Element].getChild("parameters").asInstanceOf[Element].getChild("multinomialLogit").asInstanceOf[Element]
        theModelOpt = Some(MultinomialLogit.multinomialLogitFactory(rootNode))
      }
    }
    theModelOpt match {
      case Some(theModel) =>
        theModel
      case None =>
        throw new RuntimeException(s"Cannot find a mode choice model of type ModeChoiceMultinomialLogit in file: ${modeChoiceParametersFile}")
    }
  }

}
