package beam.agentsim.agents.choice.mode

import scala.util.Random

import beam.agentsim.agents.choice.logit.MultinomialLogit.MnlData
import beam.agentsim.agents.choice.logit.{AlternativeAttributes, MultinomialLogit}
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.GeneralizedVot
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_TRANSIT, TRANSIT, WALK_TRANSIT}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scalaz.syntax._

/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(val beamServices: BeamServices, val model: MultinomialLogit)
    extends ModeChoiceCalculator {

  var expectedMaximumUtility: Double = 0.0

  def timeAndCost(mct: ModeCostTimeTransfer): BigDecimal = {
    mct.scaledTime + mct.cost
  }

  override def apply(alternatives: IndexedSeq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    if (alternatives.isEmpty) {
      None
    } else {

      val modeCostTimeTransfers = altsToModeCostTimeTransfers(alternatives)

      val bestInGroup =
      modeCostTimeTransfers groupBy (_.mode) map {
        case (_, group) => group minBy timeAndCost
      }

      val inputData = bestInGroup.map { mct =>
        val theParams: Map[String, BigDecimal] =
          Map("cost" -> mct.cost, "time" -> mct.scaledTime)
        val transferParam: Map[String, BigDecimal] = if (mct.mode.isTransit) {
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

  def utilityOf(
    mode: BeamMode,
    cost: BigDecimal,
    time: BigDecimal,
    numTransfers: Int = 0
  ): Double = {
    val variables =
      Map(
        "transfer" -> BigDecimal(numTransfers),
        "cost"     -> cost,
        "time"     -> scaleTimeByVot(time, Option(mode))
      )
    model.getUtilityOfAlternative(AlternativeAttributes(mode.value, variables))
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = {
    val modeCostTimeTransfer = altsToModeCostTimeTransfers(IndexedSeq(alternative)).head
    utilityOf(
      modeCostTimeTransfer.mode,
      modeCostTimeTransfer.cost,
      modeCostTimeTransfer.scaledTime,
      modeCostTimeTransfer.numTransfers
    )
  }

  def altsToModeCostTimeTransfers(
    alternatives: IndexedSeq[EmbodiedBeamTrip]
  ): IndexedSeq[ModeCostTimeTransfer] = {
    val transitFareDefaults =
      TransitFareDefaults.estimateTransitFares(alternatives)
    val gasolineCostDefaults =
      DrivingCostDefaults.estimateDrivingCost(alternatives, beamServices)
    val bridgeTollsDefaults =
      BridgeTollDefaults.estimateBridgeFares(alternatives, beamServices)
    val rideHailDefaults = RideHailDefaults.estimateRideHailCost(alternatives)
    alternatives.zipWithIndex.map { altAndIdx =>
      val totalCost = altAndIdx._1.tripClassifier match {
        case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
          (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.transitPrice +
          gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2) * beamServices.beamConfig.beam.agentsim.tuning.tollPrice
        case RIDE_HAIL =>
          (altAndIdx._1.costEstimate + rideHailDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice +
          bridgeTollsDefaults(altAndIdx._2) * beamServices.beamConfig.beam.agentsim.tuning.tollPrice
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
            .sum + rideHailDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice +
          bridgeTollsDefaults(altAndIdx._2) * beamServices.beamConfig.beam.agentsim.tuning.tollPrice
        case CAR =>
          altAndIdx._1.costEstimate + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(
            altAndIdx._2
          ) * beamServices.beamConfig.beam.agentsim.tuning.tollPrice
        case _ =>
          altAndIdx._1.costEstimate
      }
      val numTransfers = altAndIdx._1.tripClassifier match {
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
      ModeCostTimeTransfer(
        altAndIdx._1.tripClassifier,
        totalCost,
        scaleTimeByVot(altAndIdx._1.totalTravelTimeInSecs, Option(altAndIdx._1.tripClassifier)),
        numTransfers,
        altAndIdx._2
      )
    }
  }

}

object ModeChoiceMultinomialLogit {

  case class ModeCostTimeTransfer(
    mode: BeamMode,
    cost: BigDecimal,
    scaledTime: BigDecimal,
    numTransfers: Int,
    index: Int = -1
  )

  def buildModelFromConfig(mnlConfig: Agents.ModalBehaviors.MulitnomialLogit): MultinomialLogit = {
    val mnlData: Vector[MnlData] = Vector(
      new MnlData("COMMON", "cost", "multiplier", mnlConfig.params.cost),
      new MnlData("COMMON", "time", "multiplier", mnlConfig.params.time),
      new MnlData("car", "intercept", "intercept", mnlConfig.params.car_intercept),
      new MnlData("walk", "intercept", "intercept", mnlConfig.params.walk_intercept),
      new MnlData(
        "ride_hail",
        "intercept",
        "intercept",
        mnlConfig.params.ride_hail_intercept
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

}
