package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.logit.MultinomialLogit.MnlData
import beam.agentsim.agents.choice.logit.{AlternativeAttributes, MultinomialLogit}
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  DRIVE_TRANSIT,
  RIDE_HAIL,
  RIDE_HAIL_POOLED,
  RIDE_HAIL_TRANSIT,
  TRANSIT,
  WALK,
  WALK_TRANSIT
}
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents
import beam.sim.population.AttributesOfIndividual
import beam.utils.logging.ExponentialLazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.util.Random

/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(val beamServices: BeamServices, val model: MultinomialLogit)
    extends ModeChoiceCalculator
    with ExponentialLazyLogging {

  var expectedMaximumUtility: Double = 0.0

  override def apply(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual
  ): Option[EmbodiedBeamTrip] = {
    if (alternatives.isEmpty) {
      None
    } else {
      val modeCostTimeTransfers = altsToModeCostTimeTransfers(alternatives, attributesOfIndividual)

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

      val chosenModeOpt = model.sampleAlternative(inputData, new Random(beamServices.beamConfig.matsim.modules.global.randomSeed))
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

  def altsToModeCostTimeTransfers(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual
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
      ModeCostTimeTransfer(
        mode,
        incentivizedCost,
        scaleTimeByVot(altAndIdx._1.totalTravelTimeInSecs + waitTime, Option(mode)),
        numTransfers,
        altAndIdx._2
      )
    }
  }

  override def utilityOf(alternative: EmbodiedBeamTrip, attributesOfIndividual: AttributesOfIndividual): Double = {
    val modeCostTimeTransfer = altsToModeCostTimeTransfers(IndexedSeq(alternative), attributesOfIndividual).head
    utilityOf(
      modeCostTimeTransfer.mode,
      modeCostTimeTransfer.cost,
      modeCostTimeTransfer.scaledTime,
      modeCostTimeTransfer.numTransfers
    )
  }

  def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int = 0): Double = {
    val variables =
      Map(
        "transfer" -> numTransfers.toDouble,
        "cost"     -> (cost + scaleTimeByVot(time, Option(mode)))
      )
    model.getUtilityOfAlternative(AlternativeAttributes(mode.value, variables))
  }

}

object ModeChoiceMultinomialLogit {

  def buildModelFromConfig(mnlConfig: Agents.ModalBehaviors.MulitnomialLogit): MultinomialLogit = {
    val mnlData: Vector[MnlData] = Vector(
      new MnlData("COMMON", "cost", "multiplier", -1.0),
      new MnlData("car", "intercept", "intercept", mnlConfig.params.car_intercept),
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
