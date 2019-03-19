package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.logit.MultinomialLogit.MnlData
import beam.agentsim.agents.choice.logit.{AlternativeAttributes, MultinomialLogit}
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.ModalBehaviors
import beam.sim.population.AttributesOfIndividual
import beam.utils.logging.ExponentialLazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(val beamServices: BeamServices, val model: MultinomialLogit)
    extends ModeChoiceCalculator
    with ExponentialLazyLogging {

  var expectedMaximumUtility: Double = 0.0
  val modalBehaviors: ModalBehaviors = beamServices.getModalBehaviors()

  override def apply(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): Option[EmbodiedBeamTrip] = {
    if (alternatives.isEmpty) {
      None
    } else {
      val modeCostTimeTransfers = altsToModeCostTimeTransfers(alternatives, attributesOfIndividual, destinationActivity)
      val bestInGroup =
      modeCostTimeTransfers groupBy (_.mode) map {
        case (_, group) => group minBy timeAndCost
      }
      val inputData = bestInGroup.map { mct =>
        val theParams: Map[String, Double] =
          Map("cost" -> (mct.cost + mct.scaledTime))
        val transferParam: Map[String, Double] = if (mct.mode.isTransit) {
          Map("transfer" -> mct.numTransfers)
        } else {
          Map()
        }
        AlternativeAttributes(mct.mode.value, theParams ++ transferParam)
      }.toVector

      val chosenModeOpt = model.sampleAlternative(inputData, new Random())
      expectedMaximumUtility = model.getExpectedMaximumUtility(inputData)

      chosenModeOpt match {
        case Some(chosenMode) =>
          val chosenModeCostTime =
            bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode))
          if (chosenModeCostTime.isEmpty || chosenModeCostTime.head.index < 0) {
            None
          } else {
            Some(alternatives(chosenModeCostTime.head.index))
          }
        case None =>
          None
      }
    }
  }

  def timeAndCost(mct: ModeCostTimeTransfer): Double = {
    mct.scaledTime + mct.cost
  }

  // Generalized Time is always in hours!

  override def getGeneralizedTimeOfTrip(
    embodiedBeamTrip: EmbodiedBeamTrip,
    attributesOfIndividual: Option[AttributesOfIndividual],
    destinationActivity: Option[Activity]
  ): Double = {
    val waitingTime = embodiedBeamTrip.totalTravelTimeInSecs - embodiedBeamTrip.legs.map(_.beamLeg.duration).sum
    embodiedBeamTrip.legs
      .map(x => getGeneralizedTimeOfLeg(x, attributesOfIndividual, destinationActivity))
      .sum + getGeneralizedTime(waitingTime, None, None)
  }

  override def getGeneralizedTimeOfLeg(
    embodiedBeamLeg: EmbodiedBeamLeg,
    attributesOfIndividual: Option[AttributesOfIndividual],
    destinationActivity: Option[Activity]
  ): Double = {
    attributesOfIndividual match {
      case Some(attributes) =>
        attributes.getGeneralizedTimeOfLegForMNL(
          embodiedBeamLeg,
          this,
          beamServices,
          destinationActivity
        )
      case None =>
        embodiedBeamLeg.beamLeg.duration * modeMultipliers.getOrElse(Some(embodiedBeamLeg.beamLeg.mode), 1.0) / 3600
    }
  }

  override def getGeneralizedTime(
    time: Double,
    beamMode: Option[BeamMode] = None,
    beamLeg: Option[EmbodiedBeamLeg] = None
  ): Double = {
    time / 3600 * modeMultipliers.getOrElse(beamMode, 1.0)
  }

  def altsToModeCostTimeTransfers(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): IndexedSeq[ModeCostTimeTransfer] = {
    val walkTripStartTime = alternatives
      .find(_.tripClassifier == WALK)
      .map(_.legs.head.beamLeg.startTime)
    val transitFareDefaults =
      TransitFareDefaults.estimateTransitFares(alternatives)
    val rideHailDefaults = RideHailDefaults.estimateRideHailCost(alternatives)

    alternatives.zipWithIndex.map { altAndIdx =>
      val mode = altAndIdx._1.tripClassifier
      val totalCost: Double = mode match {
        case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
          (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.transitPrice
        case RIDE_HAIL | RIDE_HAIL_POOLED =>
          (altAndIdx._1.costEstimate + rideHailDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice
        case RIDE_HAIL_TRANSIT =>
          (altAndIdx._1.legs.view
            .filter(_.beamLeg.mode.isTransit)
            .map(_.cost)
            .sum + transitFareDefaults(
            altAndIdx._2
          )) * beamServices.beamConfig.beam.agentsim.tuning.transitPrice +
          (altAndIdx._1.legs.view
            .filter(_.isRideHail)
            .map(_.cost)
            .sum + rideHailDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice
        case _ =>
          altAndIdx._1.costEstimate
      }

      val incentive: Double = beamServices.modeIncentives.computeIncentive(attributesOfIndividual, mode)

      val incentivizedCost =
        Math.max(0, totalCost.toDouble - incentive)

      if (totalCost < incentive)
        logger.warn(
          "Mode incentive is even higher then the cost, setting cost to zero. Mode: {}, Cost: {}, Incentive: {}",
          mode,
          totalCost,
          incentive
        )

      val numTransfers = mode match {
        case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT =>
          var nVeh = -1
          var vehId = Id.create("dummy", classOf[Vehicle])
          altAndIdx._1.legs.foreach { leg =>
            if (leg.beamLeg.mode.isTransit && leg.beamVehicleId != vehId) {
              vehId = leg.beamVehicleId
              nVeh = nVeh + 1
            }
          }
          nVeh
        case _ =>
          0
      }
      assert(numTransfers >= 0)
      val scaledTime = attributesOfIndividual.getVOT(getGeneralizedTimeOfTrip(altAndIdx._1, Some(attributesOfIndividual), destinationActivity))
      ModeCostTimeTransfer(
        mode,
        incentivizedCost,
        scaledTime,
        numTransfers,
        altAndIdx._2
      )
    }
  }

  lazy val modeMultipliers: mutable.Map[Option[BeamMode], Double] =
    mutable.Map[Option[BeamMode], Double](
      Some(TRANSIT)   -> modalBehaviors.modeVotMultiplier.transit,
      Some(RIDE_HAIL) -> modalBehaviors.modeVotMultiplier.rideHail,
      Some(CAV)       -> modalBehaviors.modeVotMultiplier.CAV,
//      Some(WAITING)          -> modalBehaviors.modeVotMultiplier.waiting, TODO think of alternative for waiting. For now assume "NONE" is waiting
      Some(BIKE) -> modalBehaviors.modeVotMultiplier.bike,
      Some(WALK) -> modalBehaviors.modeVotMultiplier.walk,
      Some(CAR)  -> modalBehaviors.modeVotMultiplier.drive,
      None       -> modalBehaviors.modeVotMultiplier.waiting
    )

  lazy val poolingMultipliers: mutable.Map[automationLevel, Double] =
    mutable.Map[automationLevel, Double](
      levelLE2 -> modalBehaviors.poolingMultiplier.LevelLE2,
      level3   -> modalBehaviors.poolingMultiplier.Level3,
      level4   -> modalBehaviors.poolingMultiplier.Level4,
      level5   -> modalBehaviors.poolingMultiplier.Level5
    )

  lazy val situationMultipliers: mutable.Map[(timeSensitivity, congestionLevel, roadwayType, automationLevel), Double] =
    mutable.Map[(timeSensitivity, congestionLevel, roadwayType, automationLevel), Double](
      (highSensitivity, highCongestion, highway, levelLE2)    -> modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.LevelLE2,
      (highSensitivity, highCongestion, nonHighway, levelLE2) -> modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.LevelLE2,
      (highSensitivity, lowCongestion, highway, levelLE2)     -> modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.LevelLE2,
      (highSensitivity, lowCongestion, nonHighway, levelLE2)  -> modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.LevelLE2,
      (lowSensitivity, highCongestion, highway, levelLE2)     -> modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.LevelLE2,
      (lowSensitivity, highCongestion, nonHighway, levelLE2)  -> modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.LevelLE2,
      (lowSensitivity, lowCongestion, highway, levelLE2)      -> modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.LevelLE2,
      (lowSensitivity, lowCongestion, nonHighway, levelLE2)   -> modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.LevelLE2,
      (highSensitivity, highCongestion, highway, level3)      -> modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level3,
      (highSensitivity, highCongestion, nonHighway, level3)   -> modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level3,
      (highSensitivity, lowCongestion, highway, level3)       -> modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level3,
      (highSensitivity, lowCongestion, nonHighway, level3)    -> modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level3,
      (lowSensitivity, highCongestion, highway, level3)       -> modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level3,
      (lowSensitivity, highCongestion, nonHighway, level3)    -> modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level3,
      (lowSensitivity, lowCongestion, highway, level3)        -> modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level3,
      (lowSensitivity, lowCongestion, nonHighway, level3)     -> modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level3,
      (highSensitivity, highCongestion, highway, level4)      -> modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level4,
      (highSensitivity, highCongestion, nonHighway, level4)   -> modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level4,
      (highSensitivity, lowCongestion, highway, level4)       -> modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level4,
      (highSensitivity, lowCongestion, nonHighway, level4)    -> modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level4,
      (lowSensitivity, highCongestion, highway, level4)       -> modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level4,
      (lowSensitivity, highCongestion, nonHighway, level4)    -> modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level4,
      (lowSensitivity, lowCongestion, highway, level4)        -> modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level4,
      (lowSensitivity, lowCongestion, nonHighway, level4)     -> modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level4,
      (highSensitivity, highCongestion, highway, level5)      -> modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level5,
      (highSensitivity, highCongestion, nonHighway, level5)   -> modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level5,
      (highSensitivity, lowCongestion, highway, level5)       -> modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level5,
      (highSensitivity, lowCongestion, nonHighway, level5)    -> modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level5,
      (lowSensitivity, highCongestion, highway, level5)       -> modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level5,
      (lowSensitivity, highCongestion, nonHighway, level5)    -> modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level5,
      (lowSensitivity, lowCongestion, highway, level5)        -> modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level5,
      (lowSensitivity, lowCongestion, nonHighway, level5)     -> modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level5
    )

  override def utilityOf(
    alternative: EmbodiedBeamTrip,
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): Double = {
    val modeCostTimeTransfer =
      altsToModeCostTimeTransfers(IndexedSeq(alternative), attributesOfIndividual, destinationActivity).head
    utilityOf(
      modeCostTimeTransfer.mode,
      modeCostTimeTransfer.cost + modeCostTimeTransfer.scaledTime,
      modeCostTimeTransfer.scaledTime,
      modeCostTimeTransfer.numTransfers
    )
  }

  def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int = 0): Double = {
    val variables =
      Map(
        "transfer" -> numTransfers.toDouble,
        "cost"     -> cost
      )
    model.getUtilityOfAlternative(AlternativeAttributes(mode.value, variables))
  }

  override def computeAllDayUtility(
    trips: ListBuffer[EmbodiedBeamTrip],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual
  ): Double = trips.map(utilityOf(_, attributesOfIndividual, None)).sum // TODO: Update with destination activity
}

object ModeChoiceMultinomialLogit {

  def buildModelFromConfig(mnlConfig: Agents.ModalBehaviors.MulitnomialLogit): MultinomialLogit = {
    val mnlData: Vector[MnlData] = Vector(
      new MnlData("COMMON", "cost", "multiplier", -1.0),
      new MnlData("car", "intercept", "intercept", mnlConfig.params.car_intercept),
      new MnlData("cav", "intercept", "intercept", mnlConfig.params.cav_intercept),
      new MnlData("walk", "intercept", "intercept", mnlConfig.params.walk_intercept),
      new MnlData(
        "ride_hail",
        "intercept",
        "intercept",
        mnlConfig.params.ride_hail_intercept
      ),
      new MnlData(
        "ride_hail_pooled",
        "intercept",
        "intercept",
        mnlConfig.params.ride_hail_pooled_intercept
      ),
      new MnlData("bike", "intercept", "intercept", mnlConfig.params.bike_intercept),
      new MnlData(
        "walk_transit",
        "intercept",
        "intercept",
        mnlConfig.params.walk_transit_intercept
      ),
      new MnlData("walk_transit", "transfer", "multiplier", mnlConfig.params.transfer),
      new MnlData(
        "drive_transit",
        "intercept",
        "intercept",
        mnlConfig.params.drive_transit_intercept
      ),
      new MnlData("drive_transit", "transfer", "multiplier", mnlConfig.params.transfer),
      new MnlData(
        "ride_hail_transit",
        "intercept",
        "intercept",
        mnlConfig.params.ride_hail_transit_intercept
      ),
      new MnlData("ride_hail_transit", "transfer", "multiplier", mnlConfig.params.transfer)
    )
    MultinomialLogit(mnlData)
  }

  case class ModeCostTimeTransfer(
    mode: BeamMode,
    cost: Double,
    scaledTime: Double,
    numTransfers: Int,
    index: Int = -1
  )

}
