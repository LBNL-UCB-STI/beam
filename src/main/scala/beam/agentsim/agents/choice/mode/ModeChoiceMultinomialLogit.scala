package beam.agentsim.agents.choice.mode

import java.util.Random

import beam.agentsim.agents.choice.logit.MultinomialLogit.MnlData
import beam.agentsim.agents.choice.logit.{AlternativeAttributes, MultinomialLogit}
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit.ModeCostTimeTransfer
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, DRIVE_TRANSIT, RIDE_HAIL, TRANSIT, WALK_TRANSIT}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle



/**
  * BEAM
  */
class ModeChoiceMultinomialLogit(val beamServices: BeamServices, val model: MultinomialLogit) extends ModeChoiceCalculator {

  var expectedMaximumUtility: Double = 0.0

  override def apply(alternatives: Seq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    if (alternatives.isEmpty) {
      None
    } else {

      val modeCostTimeTransfers = altsToModeCostTimeTransfers(alternatives)

      val groupedByMode = modeCostTimeTransfers.sortBy(_.mode.value).groupBy(_.mode)

      val bestInGroup = groupedByMode.map { case (mode, modeCostTimeSegment) =>
        // Which dominates at $18/hr
        modeCostTimeSegment.map { mct => (mct.time / 3600 * 18 + mct.cost.toDouble, mct) }.minBy(_._1)._2
      }

      val inputData = bestInGroup.map{ mct =>
        val theParams = Map("cost"->mct.cost.toDouble,"time"->mct.time)
        val transferParam = if (mct.mode.isTransit()) {
          Map("transfer" -> mct.numTransfers.toDouble)
        }else{
          Map()
        }
        AlternativeAttributes(mct.mode.value,theParams ++ transferParam)
      }.toVector

      val chosenModeOpt = model.sampleAlternative(inputData, new Random())
      expectedMaximumUtility = model.getExpectedMaximumUtility(inputData)

      chosenModeOpt match {
        case Some(chosenMode) =>
          val chosenModeCostTime = bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode))

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

  def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int = 0): Double = {
    val variables = Map("transfer" -> numTransfers.toDouble, "cost" -> cost.toDouble, "time" -> time)
    model.getUtilityOfAlternative(AlternativeAttributes(mode.value, variables))
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

  def buildModelFromConfig(mnlConfig: Agents.ModalBehaviors.MulitnomialLogit): MultinomialLogit = {
    val mnlData: Vector[MnlData] = Vector(
      new MnlData("COMMON",         "cost",       "multiplier", mnlConfig.params.cost),
      new MnlData("COMMON",         "time",       "multiplier", mnlConfig.params.time),
      new MnlData("car",            "intercept",  "intercept",  mnlConfig.params.car_intercept),
      new MnlData("walk",           "intercept",  "intercept",  mnlConfig.params.walk_intercept),
      new MnlData("ride_hailing",   "intercept",  "intercept",  mnlConfig.params.ride_hailing_intercept),
      new MnlData("bike",           "intercept",  "intercept",  mnlConfig.params.bike_intercept),
      new MnlData("walk_transit",   "intercept",  "intercept",  mnlConfig.params.walk_transit_intercept),
      new MnlData("walk_transit",   "transfer",   "multiplier", mnlConfig.params.transfer),
      new MnlData("drive_transit",  "intercept",  "intercept",  mnlConfig.params.drive_transit_intercept),
      new MnlData("drive_transit",  "transfer",   "multiplier", mnlConfig.params.transfer)
    )
    MultinomialLogit(mnlData)
  }

}
