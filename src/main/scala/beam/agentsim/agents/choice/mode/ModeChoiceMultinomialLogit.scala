package beam.agentsim.agents.choice.mode

import java.io.{ByteArrayInputStream, File, FileInputStream, InputStream}
import java.util
import java.util.Random

import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, DRIVE_TRANSIT, RIDE_HAIL, TRANSIT, WALK_TRANSIT}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import org.jdom.{Document, Element}
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

  override def apply(alternatives: Seq[EmbodiedBeamTrip], choiceAttributes: Option[AttributesOfIndividual]): EmbodiedBeamTrip = {
    if (alternatives.isEmpty) {
      throw new IllegalArgumentException("Empty choice set.")
    } else {
      val inputData: util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]] = new util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]]()

      val transitFareDefaults = TransitFareDefaults.estimateTransitFares(alternatives)
      val gasolineCostDefaults = DrivingCostDefaults.estimateDrivingCost(alternatives, beamServices)
      val bridgeTollsDefaults = BridgeTollDefaults.estimateBrdigeFares(alternatives, beamServices)
      val modeCostTimeTransfers = alternatives.zipWithIndex.map { altAndIdx =>
        val totalCost = altAndIdx._1.tripClassifier match {
          case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
            (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.transitPrice + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2)
          case RIDE_HAIL =>
            altAndIdx._1.costEstimate * beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice + bridgeTollsDefaults(altAndIdx._2) * beamServices.beamConfig.beam.agentsim.tuning.tollPrice
          case CAR =>
            altAndIdx._1.costEstimate + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2) * beamServices.beamConfig.beam.agentsim.tuning.tollPrice
          case _ =>
            altAndIdx._1.costEstimate
        }
        val numTransfers = altAndIdx._1.tripClassifier match {
          case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
            var nVeh = -1
            var vehId = Id.create("dummy", classOf[Vehicle])
            altAndIdx._1.legs.foreach { leg =>
              if (leg.beamLeg.mode.isTransit() && leg.beamVehicleId != vehId) {
                vehId = leg.beamVehicleId
                nVeh = nVeh + 1
              }
            }
            nVeh
          case _ =>
            0
        }
        assert(numTransfers >= 0)
        ModeCostTimeTransfer(altAndIdx._1.tripClassifier, totalCost, altAndIdx._1.totalTravelTime, numTransfers, altAndIdx._2)
      }
      val groupedByMode = (modeCostTimeTransfers ++ ModeChoiceMultinomialLogit.defaultAlternatives).sortBy(_.mode.value).groupBy(_.mode)

      val bestInGroup = groupedByMode.map { case (mode, modeCostTimeSegment) =>
        // Which dominates at $18/hr
        modeCostTimeSegment.map { mct => (mct.time / 3600 * 18 + mct.cost.toDouble, mct) }.minBy(_._1)._2
      }

      bestInGroup.foreach { mct =>
        val altData: util.LinkedHashMap[java.lang.String, java.lang.Double] = new util.LinkedHashMap[java.lang.String, java.lang.Double]()
        altData.put("cost", mct.cost.toDouble)
        altData.put("time", mct.time)
        if (mct.mode.isTransit()){
          altData.put("transfer", mct.numTransfers.toDouble)
        }
        inputData.put(mct.mode.value, altData)
      }

      val chosenMode = try{
        model.makeRandomChoice(inputData, new Random())
      }catch{
        case e: RuntimeException if e.getMessage.startsWith("Cannot create a CDF") =>
          "walk"
      }
      expectedMaximumUtility = model.getExpectedMaximumUtility
      model.clear()
      val chosenModeCostTime = bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode))

      if (chosenModeCostTime.isEmpty) {
        throw new RuntimeException("No choice was made.")
      } else {
        alternatives(chosenModeCostTime.head.index)
      }
    }
  }

}

object ModeChoiceMultinomialLogit {
  case class ModeCostTimeTransfer(mode: BeamMode, cost: BigDecimal, time: Double, numTransfers: Int, index: Int = -1)

  val defaultAlternatives = Vector(
    ModeCostTimeTransfer(BeamMode.WALK,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.CAR,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.RIDE_HAIL,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.BIKE,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.DRIVE_TRANSIT,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue),
    ModeCostTimeTransfer(BeamMode.WALK_TRANSIT,BigDecimal(Double.MaxValue),Double.PositiveInfinity, Int.MaxValue)
  )

  def apply(beamServices: BeamServices): ModeChoiceMultinomialLogit = {
    new ModeChoiceMultinomialLogit(beamServices,ModeChoiceMultinomialLogit.parseInputForMNL(beamServices))
  }

  def parseFromInputStream(is: InputStream): Option[MultinomialLogit] = {
    val builder: SAXBuilder = new SAXBuilder()
    val document: Document = builder.build(is).asInstanceOf[Document]
    var theModelOpt: Option[MultinomialLogit] = None

    document.getRootElement.getChildren.asScala.foreach{child =>
      if(child.asInstanceOf[Element].getName.equalsIgnoreCase("mnl")){
        val rootNode = child.asInstanceOf[Element].getChild("parameters").asInstanceOf[Element].getChild("multinomialLogit").asInstanceOf[Element]
        theModelOpt = Some(MultinomialLogit.multinomialLogitFactory(rootNode))
      }
    }

    theModelOpt
  }

  def parseInputForMNL(beamServices: BeamServices): MultinomialLogit = {
    val modeChoiceParametersFile = beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile

    val theModelOpt = parseFromInputStream(new FileInputStream(new File(modeChoiceParametersFile)))

    theModelOpt match {
      case Some(theModel) =>
        theModel
      case None =>
        throw new RuntimeException(s"Cannot find a mode choice model of type ModeChoiceMultinomialLogit in file: ${modeChoiceParametersFile}")
    }
  }

  def fromContentString(beamServices: BeamServices, content: String): ModeChoiceMultinomialLogit = {
    val is = new ByteArrayInputStream(content.getBytes("UTF-8"))

    parseFromInputStream(is) match {
      case Some(theModel) =>
        new ModeChoiceMultinomialLogit(beamServices, theModel)
      case None =>
        throw new RuntimeException(s"Cannot find a mode choice model of type ModeChoiceMultinomialLogit in content: ${content}")
    }
  }

}
