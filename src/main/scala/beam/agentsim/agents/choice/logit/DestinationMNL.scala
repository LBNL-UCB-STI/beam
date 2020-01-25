package beam.agentsim.agents.choice.logit

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode

object DestinationMNL {

  type DestinationMNLConfig = Map[DestinationMNL.DestinationParameters, UtilityFunctionOperation]

  type TripMNLConfig = Map[DestinationMNL.TripParameters, UtilityFunctionOperation]

  val DefaultMNLParameters: DestinationMNLConfig = Map(
    DestinationParameters.AccessCost      -> UtilityFunctionOperation.Multiplier(-1.0),
    DestinationParameters.EgressCost      -> UtilityFunctionOperation.Multiplier(-1.0),
    DestinationParameters.SchedulePenalty -> UtilityFunctionOperation.Multiplier(-1.0),
    DestinationParameters.ActivityBenefit -> UtilityFunctionOperation.Multiplier(1.0)
  )

  val TripMNLParameters: TripMNLConfig = Map(
    TripParameters.ExpMaxUtility -> UtilityFunctionOperation.Multiplier(1.0)
  )

  val NoTripMNLParameters: TripMNLConfig = Map(
    TripParameters.ASC -> UtilityFunctionOperation.Intercept(1.0)
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

  def prettyPrintAlternatives(params: Map[DestinationMNL.DestinationParameters, Double]): String = {
    params
      .map {
        case (param, value) =>
          f"${DestinationParameters.shortName(param)}=$value%.2f".padTo(10, ' ')
      }
      .mkString(s"", " ", ": ")
  }

  sealed trait DestinationParameters

  object DestinationParameters {
    final case object AccessCost extends DestinationParameters with Serializable
    final case object EgressCost extends DestinationParameters with Serializable
    final case object SchedulePenalty extends DestinationParameters with Serializable
    final case object ActivityBenefit extends DestinationParameters with Serializable

    def shortName(parameter: DestinationParameters): String = parameter match {
      case AccessCost      => "acc"
      case EgressCost      => "eg"
      case SchedulePenalty => "pen"
      case ActivityBenefit => "act"
    }
  }

  sealed trait TripParameters

  object TripParameters {
    final case object ASC extends TripParameters with Serializable
    final case object ExpMaxUtility extends TripParameters with Serializable

    def shortName(parameter: TripParameters): String = parameter match {
      case ASC           => "asc"
      case ExpMaxUtility => "util"
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

  def toUtilityParameters(timesAndCost: TimesAndCost): Map[DestinationMNL.DestinationParameters, Double] = {
    Map(
      DestinationParameters.AccessCost      -> timesAndCost.accessGeneralizedCost,
      DestinationParameters.EgressCost      -> timesAndCost.returnGeneralizedCost,
      DestinationParameters.SchedulePenalty -> timesAndCost.schedulePenalty,
      DestinationParameters.ActivityBenefit -> timesAndCost.activityBenefit
    )
  }
}
