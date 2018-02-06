package beam.agentsim.agents.choice.mode

import java.io.{ByteArrayInputStream, File, FileInputStream, InputStream}
import java.util
import java.util.Random

import beam.agentsim.agents.choice.logit.{AlternativeAttributes, MultinomialLogit}
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, DRIVE_TRANSIT, RIDE_HAIL, TRANSIT, WALK_TRANSIT}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import org.jdom.input.SAXBuilder
import org.jdom.{Document, Element}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._


/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(val beamServices: BeamServices, val model: MultinomialLogit) extends ModeChoiceCalculator {

  var expectedMaximumUtility: Double = 0.0

//  override def clone(): ModeChoiceCalculator = {
//    val mnl: MultinomialLogit = this.model.clone()
//    new ModeChoiceMultinomialLogit(beamServices, mnl)
//  }

  override def apply(alternatives: Seq[EmbodiedBeamTrip], choiceAttributes: Option[AttributesOfIndividual]): EmbodiedBeamTrip = {
    if (alternatives.isEmpty) {
      throw new IllegalArgumentException("Empty choice set.")
    } else {

      val modeCostTimeTransfers = altsToModeCostTimeTransfers(alternatives)

      val groupedByMode = modeCostTimeTransfers.sortBy(_.mode.value).groupBy(_.mode)

      val bestInGroup = groupedByMode.map { case (mode, modeCostTimeSegment) =>
        // Which dominates at $18/hr
        modeCostTimeSegment.map { mct => (mct.time / 3600 * 18 + mct.cost.toDouble, mct) }.minBy(_._1)._2
      }

      val inputData = bestInGroup.map{ mct =>
        val theParams = if (mct.mode.isTransit()) {
          Map("transfer" -> mct.numTransfers.toDouble)
        }else{
          Map()
        } ++ Map(("cost"->mct.cost.toDouble),("time"->mct.time))
        AlternativeAttributes(mct.mode.value,theParams)
      }.toVector

      val chosenMode = try {
        model.sampleAlternative(inputData, new Random())
      } catch {
        case e: RuntimeException if e.getMessage.startsWith("Cannot create a CDF") =>
          // This should be fixed (see issue #202) and never throw, but leaving this catch just in case
          return alternatives(chooseRandomAlternativeIndex(alternatives))
      }
      expectedMaximumUtility = model.getExpectedMaximumUtility(inputData)
      val chosenModeCostTime = bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode))

      if (chosenModeCostTime.isEmpty || chosenModeCostTime.head.index < 0) {
        throw new RuntimeException("No choice was made.")
      } else {
        alternatives(chosenModeCostTime.head.index)
      }
    }
  }

  def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int = 0): Double = {
    val theParams = if (mode.isTransit()) {
      Map("transfer" -> numTransfers.toDouble)
    }else{
      Map()
    } ++ Map(("cost"->cost.toDouble),("time"->time))
    model.getUtilityOfAlternative(AlternativeAttributes(mode.value,theParams))
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = {
    val modeCostTimeTransfer = altsToModeCostTimeTransfers(Seq(alternative)).head
    utilityOf(modeCostTimeTransfer.mode,modeCostTimeTransfer.cost.toDouble,modeCostTimeTransfer.time,modeCostTimeTransfer.numTransfers)
  }

  def altsToModeCostTimeTransfers(alternatives: Seq[EmbodiedBeamTrip]): Seq[ModeCostTimeTransfer] = {
    val transitFareDefaults = TransitFareDefaults.estimateTransitFares(alternatives)
    val gasolineCostDefaults = DrivingCostDefaults.estimateDrivingCost(alternatives, beamServices)
    val bridgeTollsDefaults = BridgeTollDefaults.estimateBridgeFares(alternatives, beamServices)
    alternatives.zipWithIndex.map { altAndIdx =>
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
  }

}

object ModeChoiceMultinomialLogit {

  case class ModeCostTimeTransfer(mode: BeamMode, cost: BigDecimal, time: Double, numTransfers: Int, index: Int = -1)

  def apply(beamServices: BeamServices): ModeChoiceMultinomialLogit = {
    new ModeChoiceMultinomialLogit(beamServices, ModeChoiceMultinomialLogit.parseInputForMNL(beamServices))
  }

  def parseFromInputStream(is: InputStream): Option[MultinomialLogit] = {
    val builder: SAXBuilder = new SAXBuilder()
    val document: Document = builder.build(is).asInstanceOf[Document]
    var theModelOpt: Option[MultinomialLogit] = None

//    document.getRootElement.getChildren.asScala.foreach { child =>
//      if (child.asInstanceOf[Element].getName.equalsIgnoreCase("mnl")) {
//        val rootNode = child.asInstanceOf[Element].getChild("parameters").asInstanceOf[Element].getChild("multinomialLogit").asInstanceOf[Element]
//        theModelOpt = Some(MultinomialLogit.multinomialLogitFactory(rootNode))
//      }
//    }

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
