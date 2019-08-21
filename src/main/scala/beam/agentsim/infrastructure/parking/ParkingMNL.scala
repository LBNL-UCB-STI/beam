package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation

object ParkingMNL {

  type ParkingMNLConfig = Map[ParkingMNL.Parameters, UtilityFunctionOperation]

  val DefaultMNLParameters: ParkingMNLConfig = Map(
    Parameters.ParkingTicketCost                     -> UtilityFunctionOperation.Multiplier(-1.0),
    Parameters.RangeAnxietyCost                      -> UtilityFunctionOperation.Multiplier(-1.0),
    Parameters.WalkingEgressCost                     -> UtilityFunctionOperation.Multiplier(-1.0),
    Parameters.HomeActivityPrefersResidentialParking -> UtilityFunctionOperation.Multiplier(1.0)
  )

  /**
    * used to determine charging choice, range anxiety
    *
    * @param primaryFuelLevelInJoules range of vehicle in meters
    * @param primaryFuelConsumptionInJoulePerMeter fuel consumption rate
    * @param remainingTourDistance distance agent expects to travel
    * @param distanceSafetyMargin a bounds test
    */
  case class RemainingTripData(
    primaryFuelLevelInJoules: Double = 0.0,
    primaryFuelConsumptionInJoulePerMeter: Double = 0.0,
    remainingTourDistance: Double = 0.0,
    distanceSafetyMargin: Double = 0.0
  ) {

    def agentCanCompleteTour(withAddedFuelInJoules: Double = 0.0): Boolean = {
      val newRange
        : Double = ((primaryFuelLevelInJoules + withAddedFuelInJoules) / primaryFuelConsumptionInJoulePerMeter) - distanceSafetyMargin
      newRange > remainingTourDistance
    }

    /**
      * models range anxiety, from 0 (no anxiety) to 1 (no vehicle range proportional to remaining trip)
      *
      * @param withAddedFuelInJoules fuel provided by a charging source which we are evaluating
      * @return range anxiety factor
      */
    def rangeAnxiety(withAddedFuelInJoules: Double = 0.0): Double = {
      if (remainingTourDistance == 0) 0
      else {
        val newRange
          : Double = ((primaryFuelLevelInJoules + withAddedFuelInJoules) / primaryFuelConsumptionInJoulePerMeter) - distanceSafetyMargin
        1 - math.max(0, math.min(1, newRange / remainingTourDistance))
      }
    }
  }

  sealed trait Parameters

  object Parameters {
    final case object ParkingTicketCost extends Parameters with Serializable
    final case object WalkingEgressCost extends Parameters with Serializable
    final case object RangeAnxietyCost extends Parameters with Serializable
    final case object HomeActivityPrefersResidentialParking extends Parameters with Serializable
  }
}
