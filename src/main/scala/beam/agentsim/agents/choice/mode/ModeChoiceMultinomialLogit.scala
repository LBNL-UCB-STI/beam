package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.logit._
import beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.EmbodiedBeamTrip
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
class ModeChoiceMultinomialLogit(val beamServices: BeamServices, val model: MultinomialLogit[String, String])
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
        (mct.mode.value, theParams ++ transferParam)
      }.toMap

      val chosenModeOpt = model.sampleAlternative(inputData, new Random())
      expectedMaximumUtility = model.getExpectedMaximumUtility(inputData).getOrElse(0)

      chosenModeOpt match {
        case Some(chosenMode) =>
          val chosenModeCostTime =
            bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode.alternativeType))
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
      val waitTime = mode match {
        case RIDE_HAIL | RIDE_HAIL_POOLED =>
          altAndIdx._1.legs.head.beamLeg.startTime - walkTripStartTime.getOrElse(
            altAndIdx._1.legs.head.beamLeg.startTime
          )
        case RIDE_HAIL_TRANSIT =>
          0 // TODO getting this would require we put wait time into EmbodiedBeamLeg, which is the right next step
        case _ =>
          0
      }
      assert(numTransfers >= 0)
      val scaledTime = altAndIdx._1.legs
        .map(
          x =>
            attributesOfIndividual
              .getVOT(x, modeMultipliers, situationMultipliers, poolingMultipliers, beamServices, destinationActivity)
        )
        .sum +
      attributesOfIndividual.getModeVotMultiplier(None, modeMultipliers) * attributesOfIndividual.unitConversionVOTT(
        waitTime
      )
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
    model.getUtilityOfAlternative(mode.value, variables).getOrElse(0)
  }

  override def computeAllDayUtility(
    trips: ListBuffer[EmbodiedBeamTrip],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual
  ): Double = trips.map(utilityOf(_, attributesOfIndividual, None)).sum // TODO: Update with destination activity
}

object ModeChoiceMultinomialLogit {

  def buildModelFromConfig(mnlConfig: Agents.ModalBehaviors.MulitnomialLogit): MultinomialLogit[String, String] = {

    val commonUtility = Map { ("COMMON", UtilityFunctionOperation("multiplier", -1)) }

    val mnlUtilityFunctions: Map[String, Map[String, UtilityFunctionOperation]] = Map {
      "car" -> Map(
        "intercept" ->
        UtilityFunctionOperation("intercept", mnlConfig.params.car_intercept)
      )
      "cav"       -> Map("intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.cav_intercept))
      "walk"      -> Map("intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.walk_intercept))
      "ride_hail" -> Map("intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.ride_hail_intercept))
      "walk"      -> Map("intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.walk_intercept))
      "ride_hail_pooled" -> Map(
        "intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.ride_hail_pooled_intercept)
      )
      "ride_hail_transit" -> Map(
        "intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.ride_hail_transit_intercept),
        "transfer"  -> UtilityFunctionOperation("multiplier", mnlConfig.params.transfer)
      )
      "bike" -> Map("intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.bike_intercept))
      "walk_transit" -> Map(
        "intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.walk_transit_intercept),
        "transfer"  -> UtilityFunctionOperation("multiplier", mnlConfig.params.transfer)
      )
      "drive_transit" -> Map(
        "intercept" -> UtilityFunctionOperation("intercept", mnlConfig.params.drive_transit_intercept),
        "transfer"  -> UtilityFunctionOperation("multiplier", mnlConfig.params.transfer)
      )
    }

//    val commonUtility = Some(
    //      UtilityFunction[String, String](
    //        "COMMON",
    //        Set(UtilityFunctionParam("cost", UtilityFunctionParamType("multiplier"), -1))
    //      )
    //    )

//    val mnlUtilityFunctions: Vector[UtilityFunction[String, String]] = Vector(
//      UtilityFunction[String, String](
//        "car",
//        Set(UtilityFunctionParam("intercept", UtilityFunctionParamType("intercept"), mnlConfig.params.car_intercept))
//      ),
//      UtilityFunction[String, String](
//        "cav",
//        Set(UtilityFunctionParam("intercept", UtilityFunctionParamType("intercept"), mnlConfig.params.cav_intercept))
//      ),
//      UtilityFunction[String, String](
//        "walk",
//        Set(UtilityFunctionParam("intercept", UtilityFunctionParamType("intercept"), mnlConfig.params.walk_intercept))
//      ),
//      UtilityFunction[String, String](
//        "ride_hail",
//        Set(
//          UtilityFunctionParam("intercept", UtilityFunctionParamType("intercept"), mnlConfig.params.ride_hail_intercept)
//        )
//      ),
//      UtilityFunction[String, String](
//        "ride_hail_pooled",
//        Set(
//          UtilityFunctionParam(
//            "intercept",
//            UtilityFunctionParamType("intercept"),
//            mnlConfig.params.ride_hail_pooled_intercept
//          )
//        )
//      ),
//      UtilityFunction[String, String](
//        "ride_hail_transit",
//        Set(
//          UtilityFunctionParam(
//            "intercept",
//            UtilityFunctionParamType("intercept"),
//            mnlConfig.params.ride_hail_transit_intercept
//          ),
//          UtilityFunctionParam(
//            "transfer",
//            UtilityFunctionParamType("multiplier"),
//            mnlConfig.params.transfer
//          )
//        )
//      ),
//      UtilityFunction[String, String](
//        "bike",
//        Set(UtilityFunctionParam("intercept", UtilityFunctionParamType("intercept"), mnlConfig.params.bike_intercept))
//      ),
//      UtilityFunction[String, String](
//        "walk_transit",
//        Set(
//          UtilityFunctionParam(
//            "intercept",
//            UtilityFunctionParamType("intercept"),
//            mnlConfig.params.walk_transit_intercept
//          ),
//          UtilityFunctionParam("transfer", UtilityFunctionParamType("multiplier"), mnlConfig.params.transfer)
//        )
//      ),
//      UtilityFunction[String, String](
//        "drive_transit",
//        Set(
//          UtilityFunctionParam(
//            "intercept",
//            UtilityFunctionParamType("intercept"),
//            mnlConfig.params.drive_transit_intercept
//          ),
//          UtilityFunctionParam("transfer", UtilityFunctionParamType("multiplier"), mnlConfig.params.transfer)
//        )
//      ),
//    )

//    val mnlData: Vector[MnlData] = Vector(
//      new MnlData("COMMON", "cost", "multiplier", -1.0),
//      new MnlData("car", "intercept", "intercept", mnlConfig.params.car_intercept),
//      new MnlData("cav", "intercept", "intercept", mnlConfig.params.cav_intercept),
//      new MnlData("walk", "intercept", "intercept", mnlConfig.params.walk_intercept),
//      new MnlData(
//        "ride_hail",
//        "intercept",
//        "intercept",
//        mnlConfig.params.ride_hail_intercept
//      ),
//      new MnlData(
//        "ride_hail_pooled",
//        "intercept",
//        "intercept",
//        mnlConfig.params.ride_hail_pooled_intercept
//      ),
//      new MnlData("bike", "intercept", "intercept", mnlConfig.params.bike_intercept),
//      new MnlData(
//        "walk_transit",
//        "intercept",
//        "intercept",
//        mnlConfig.params.walk_transit_intercept
//      ),
//      new MnlData("walk_transit", "transfer", "multiplier", mnlConfig.params.transfer),
//      new MnlData(
//        "drive_transit",
//        "intercept",
//        "intercept",
//        mnlConfig.params.drive_transit_intercept
//      ),
//      new MnlData("drive_transit", "transfer", "multiplier", mnlConfig.params.transfer),
//      new MnlData(
//        "ride_hail_transit",
//        "intercept",
//        "intercept",
//        mnlConfig.params.ride_hail_transit_intercept
//      ),
//      new MnlData("ride_hail_transit", "transfer", "multiplier", mnlConfig.params.transfer)
//    )

    logit.MultinomialLogit(
      mnlUtilityFunctions,
      commonUtility
    )
  }

  case class ModeCostTimeTransfer(
    mode: BeamMode,
    cost: Double,
    scaledTime: Double,
    numTransfers: Int,
    index: Int = -1
  )

}
