package beam.agentsim.agents.choice.mode

import java.util
import java.util.Random

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{Mandatory, TourType}
import beam.agentsim.agents.choice.mode.ModeChoiceLCCM.ModeChoiceData
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, RIDEHAIL, TRANSIT, WALK}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  * ModeChoiceLCCM
  *
  * Data used by mode choice model:
  * --vehicleTime
  * --walkTime
  * --bikeTime
  * --waitTime
  * --cost
  *
  * Data used by class membership model:
  * --surplus
  * --income
  * --householdSize
  * --male
  * --numCars
  * --numBikes
  */
class ModeChoiceLCCM(val beamServices: BeamServices, val lccm: LatentClassChoiceModel) extends ModeChoiceCalculator {

  override def apply(alternatives: Vector[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    alternatives.isEmpty match {
      case true =>
        None
      case false =>
        val modeChoiceInputData: util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]] = new util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]]()
        val classMembershipInputData: util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]] = new util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]]()

        val transitFareDefaults: Vector[BigDecimal] = TransitFareDefaults.estimateTransitFares(alternatives)
        val gasolineCostDefaults: Vector[BigDecimal] = DrivingCostDefaults.estimateDrivingCost(alternatives, beamServices)
        val bridgeTollsDefaults: Vector[BigDecimal] = BridgeTollDefaults.estimateBrdigeFares(alternatives, beamServices)

        if (bridgeTollsDefaults.map(_.toDouble).sum > 0) {
          val i = 0
        }

        val modeChoiceAlternatives: Vector[ModeChoiceData] = alternatives.zipWithIndex.map { altAndIdx =>
          val totalCost = altAndIdx._1.tripClassifier match {
            case TRANSIT =>
              (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2))*beamServices.beamConfig.beam.agentsim.tuning.transitPrice + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2)
            case RIDEHAIL =>
              altAndIdx._1.costEstimate*beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice + bridgeTollsDefaults(altAndIdx._2)*beamServices.beamConfig.beam.agentsim.tuning.tollPrice
            case CAR =>
              altAndIdx._1.costEstimate + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2)*beamServices.beamConfig.beam.agentsim.tuning.tollPrice
            case _ =>
              altAndIdx._1.costEstimate
          }
          val walkTime = altAndIdx._1.legs.filter(_.beamLeg.mode == WALK).map(_.beamLeg.duration).sum
          val bikeTime = altAndIdx._1.legs.filter(_.beamLeg.mode == BIKE).map(_.beamLeg.duration).sum
          val vehicleTime = altAndIdx._1.legs.filter(_.beamLeg.mode != WALK).filter(_.beamLeg.mode != BIKE).map(_.beamLeg.duration).sum
          val waitTime = altAndIdx._1.totalTravelTime - walkTime - vehicleTime
          ModeChoiceData(altAndIdx._1.tripClassifier, Mandatory,vehicleTime, walkTime, waitTime, bikeTime, totalCost.toDouble)
        }
        // TODO do I need to add defaults?
        // ++ ModeChoiceLCCM.defaultAlternatives

        modeChoiceAlternatives.foreach { alt =>
          val altData: util.LinkedHashMap[java.lang.String, java.lang.Double] = new util.LinkedHashMap[java.lang.String, java.lang.Double]()
          altData.put("cost", alt.cost.toDouble)
          altData.put("walkTime", alt.walkTime)
          altData.put("bikeTime", alt.bikeTime)
          altData.put("waitTime", alt.waitTime)
          altData.put("vehicleTime", alt.vehicleTime)
          modeChoiceInputData.put(alt.mode.value, altData)
        }

        val chosenMode = "CAR"
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

  override def clone(): ModeChoiceCalculator = {
    val lccmClone: LatentClassChoiceModel = lccm.clone().asInstanceOf[LatentClassChoiceModel]
    new ModeChoiceLCCM(beamServices,lccmClone)
  }
}
object ModeChoiceLCCM{
  def apply(beamServices: BeamServices): ModeChoiceLCCM ={
    val lccm = new LatentClassChoiceModel(beamServices)
    new ModeChoiceLCCM(beamServices,lccm)
  }
  case class ModeChoiceData(mode: BeamMode,tourType: TourType, vehicleTime: Double, walkTime: Double, waitTime: Double, bikeTime: Double, cost: Double)
  case class ClassMembershipData(tourType: TourType, surplus: Double, income: Double, householdSize: Double, isMale: Double, numCars: Double, numBikes: Double)

  val defaultAlternatives = Vector(
    ModeChoiceData(BeamMode.WALK,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
    ModeChoiceData(BeamMode.CAR,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
    ModeChoiceData(BeamMode.TRANSIT,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
    ModeChoiceData(BeamMode.BIKE,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
    ModeChoiceData(BeamMode.RIDEHAIL,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity)
  )
}
