package beam.agentsim.agents.choice.logit

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode

object DestinationMNL {

  type DestinationMNLConfig = Map[DestinationMNL.Parameters, UtilityFunctionOperation]

  val DefaultMNLParameters: DestinationMNLConfig = Map(
    Parameters.AccessCost      -> UtilityFunctionOperation.Multiplier(-1.0),
    Parameters.EgressCost      -> UtilityFunctionOperation.Multiplier(-1.0),
    Parameters.SchedulePenalty -> UtilityFunctionOperation.Multiplier(-1.0),
    Parameters.ActivityBenefit -> UtilityFunctionOperation.Multiplier(1.0)
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
  ) {}

  def prettyPrintAlternatives(params: Map[DestinationMNL.Parameters, Double]): String = {
    params
      .map {
        case (param, value) =>
          f"${Parameters.shortName(param)}=$value%.2f".padTo(10, ' ')
      }
      .mkString(s"", " ", ": ")
  }

  sealed trait Parameters

  object Parameters {
    final case object AccessCost extends Parameters with Serializable
    final case object EgressCost extends Parameters with Serializable
    final case object SchedulePenalty extends Parameters with Serializable
    final case object ActivityBenefit extends Parameters with Serializable

    def shortName(parameter: Parameters): String = parameter match {
      case AccessCost      => "acc"
      case EgressCost      => "eg"
      case SchedulePenalty => "pen"
      case ActivityBenefit => "act"
    }
  }
  case class SupplementaryTripAlternative(
    taz: TAZ,
    activityType: String,
    mode: BeamMode,
    activityDuration: Int,
    startTime: Int
  )

  case class TimesAndCost(
    accessTime: Double = 0,
    returnTime: Double = 0,
    accessGeneralizedCost: Double = 0,
    returnGeneralizedCost: Double = 0,
    schedulePenalty: Double = 0,
    activityBenefit: Double = 0
  )

  def toUtilityParameters(timesAndCost: TimesAndCost): Map[DestinationMNL.Parameters, Double] = {
    Map(
      Parameters.AccessCost      -> timesAndCost.accessGeneralizedCost,
      Parameters.EgressCost      -> timesAndCost.returnGeneralizedCost,
      Parameters.SchedulePenalty -> timesAndCost.schedulePenalty,
      Parameters.ActivityBenefit -> timesAndCost.activityBenefit
    )
  }
}
