package beam.agentsim.agents.choice.logit

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.sim.config.BeamConfig

object DestinationChoiceModel {
  def apply(beamConfig: BeamConfig) = new DestinationChoiceModel(beamConfig)

  def toUtilityParameters(timesAndCost: TimesAndCost): Map[DestinationParameters, Double] = {
    Map(
      DestinationParameters.AccessCost      -> timesAndCost.accessGeneralizedCost,
      DestinationParameters.EgressCost      -> timesAndCost.returnGeneralizedCost,
      DestinationParameters.SchedulePenalty -> timesAndCost.schedulePenalty,
      DestinationParameters.ActivityBenefit -> timesAndCost.activityBenefit
    )
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

  type DestinationMNLConfig = Map[DestinationParameters, UtilityFunctionOperation]

  type TripMNLConfig = Map[TripParameters, UtilityFunctionOperation]

  type ActivityVOTs = Map[String, Double]

  type ActivityRates = Map[Int, Map[String, Double]]
}

class DestinationChoiceModel(
  val beamConfig: BeamConfig
) {

  val DefaultMNLParameters: DestinationChoiceModel.DestinationMNLConfig = Map(
    DestinationChoiceModel.DestinationParameters.AccessCost      -> UtilityFunctionOperation.Multiplier(-1.0),
    DestinationChoiceModel.DestinationParameters.EgressCost      -> UtilityFunctionOperation.Multiplier(-1.0),
    DestinationChoiceModel.DestinationParameters.SchedulePenalty -> UtilityFunctionOperation.Multiplier(-1.0),
    DestinationChoiceModel.DestinationParameters.ActivityBenefit -> UtilityFunctionOperation.Multiplier(1.0)
  )

  val TripMNLParameters: DestinationChoiceModel.TripMNLConfig = Map(
    DestinationChoiceModel.TripParameters.ExpMaxUtility -> UtilityFunctionOperation.Multiplier(1.0),
    DestinationChoiceModel.TripParameters.ASC           -> UtilityFunctionOperation.Intercept(1.0)
  )

  val DefaultActivityRates: DestinationChoiceModel.ActivityRates = Map(
    0  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> -5.0),
    1  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> -5.0),
    2  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> -5.0),
    3  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> -5.0),
    4  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> -3.0),
    5  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> -1.0),
    6  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 1.0),
    7  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 2.0),
    8  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 2.0),
    9  -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 2.0),
    10 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 1.0),
    11 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 1.0),
    12 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 1.0),
    13 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 3.0),
    14 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 3.0),
    15 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 3.0),
    16 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 2.0),
    17 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 2.0),
    18 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 3.0),
    19 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 3.0),
    20 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 2.0),
    21 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 1.0),
    22 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 1.0),
    23 -> Map[String, Double]("Home" -> 0.8, "Work" -> 1.0, "Other" -> 0.0)
  )

  val DefaultActivityVOTs: DestinationChoiceModel.ActivityVOTs = Map(
    "Home"  -> 0.8,
    "Work"  -> 1.0,
    "Other" -> 3.0
  )

}
