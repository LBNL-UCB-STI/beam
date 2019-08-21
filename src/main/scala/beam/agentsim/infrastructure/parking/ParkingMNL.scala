package beam.agentsim.infrastructure.parking

object ParkingMNL {

  /**
    * these are the multipliers against the terms which represent these different parking concerns
    *
    * @param rangeAnxiety
    * @param distance
    * @param parkingCosts
    */
  case class Config(
    rangeAnxiety: Double = 1.0,
    distance: Double = 1.0,
    parkingCosts: Double = 1.0
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
  }

  sealed trait Parameters

  object Parameters {
    final case object StallCost extends Parameters with Serializable
    final case object WalkingEgressCost extends Parameters with Serializable
    final case object RangeAnxietyCost extends Parameters with Serializable
  }
}
