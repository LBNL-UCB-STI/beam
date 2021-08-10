package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ParkingAlternative

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
    * @param rangeAnxietyBuffer the number of meters our remaining range needs to exceed our remaining tour in order to feel no anxiety
    */
  case class RemainingTripData(
    primaryFuelLevelInJoules: Double = 0.0,
    primaryFuelConsumptionInJoulePerMeter: Double = 0.0,
    remainingTourDistance: Double = 0.0,
    rangeAnxietyBuffer: Double = 20000.0
  ) {

    /**
      * models range anxiety with a piecewise function.
      *
      * from 0 (no anxiety) to 1 (anxiety) when we still have enough fuel to complete our tour
      * jumps to 2 when we don't have enough fuel to complete our tour
      *
      * @param withAddedFuelInJoules fuel provided by a charging source which we are evaluating
      * @return range anxiety factor
      */
    def rangeAnxiety(withAddedFuelInJoules: Double = 0.0): Double = {
      if (remainingTourDistance == 0) 0.0
      else {
        val newRange: Double =
          (primaryFuelLevelInJoules + withAddedFuelInJoules) / primaryFuelConsumptionInJoulePerMeter
        if (newRange > remainingTourDistance) {
          val excessFuelProportion: Double = newRange / (remainingTourDistance + rangeAnxietyBuffer)
          1 - math.min(1.0, math.max(0.0, excessFuelProportion))
        } else {
          2.0 // step up to 2, an urgent need to find other alternatives
        }
      }
    }
  }

  def prettyPrintAlternatives(params: Map[ParkingMNL.Parameters, Double]): String = {
    params
      .map { case (param, value) =>
        f"${Parameters.shortName(param)}=$value%.2f".padTo(10, ' ')
      }
      .mkString("", " ", ": ")
  }

  sealed trait Parameters

  object Parameters {
    final case object ParkingTicketCost extends Parameters with Serializable
    final case object WalkingEgressCost extends Parameters with Serializable
    final case object RangeAnxietyCost extends Parameters with Serializable
    final case object InsufficientRangeCost extends Parameters with Serializable
    final case object DrivingTimeCost extends Parameters with Serializable
    final case object QueueingTimeCost extends Parameters with Serializable
    final case object ChargingTimeCost extends Parameters with Serializable
    final case object HomeActivityPrefersResidentialParking extends Parameters with Serializable

    def shortName(parameter: Parameters): String = parameter match {
      case ParkingTicketCost                     => "park"
      case WalkingEgressCost                     => "dist"
      case RangeAnxietyCost                      => "anx"
      case InsufficientRangeCost                 => "range"
      case DrivingTimeCost                       => "time"
      case QueueingTimeCost                      => "queue"
      case ChargingTimeCost                      => "charge"
      case HomeActivityPrefersResidentialParking => "home"
    }
  }
}
