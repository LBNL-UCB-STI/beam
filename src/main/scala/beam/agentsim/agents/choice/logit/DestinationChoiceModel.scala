package beam.agentsim.agents.choice.logit

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.utils.io.IOUtils

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

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

  type ActivityRates = Map[String, Map[Int, Double]]

  type ActivityDurations = Map[String, Double]
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

  val DefaultActivityRates: DestinationChoiceModel.ActivityRates =
    Map(
      "Other" -> Map[Int, Double](
        0  -> -5.0,
        1  -> -5.0,
        2  -> -5.0,
        3  -> -5.0,
        4  -> -3.0,
        5  -> -1.0,
        6  -> 1.0,
        7  -> 2.0,
        8  -> 2.0,
        9  -> 2.0,
        11 -> 2.0,
        10 -> 1.0,
        12 -> 3.0,
        13 -> 3.0,
        14 -> 3.0,
        15 -> 2.0,
        16 -> 2.0,
        17 -> 2.0,
        18 -> 3.0,
        19 -> 3.0,
        20 -> 2.0,
        21 -> 1.0,
        22 -> 1.0,
        23 -> 0.0
      )
    )

  val DefaultActivityVOTs: DestinationChoiceModel.ActivityVOTs = Map(
    "Home"  -> 0.8,
    "Work"  -> 1.0,
    "Other" -> 2.0
  )

  val DefaultActivityDurations: DestinationChoiceModel.ActivityDurations = Map(
    "Other" -> 15.0 * 60.0
  )

  val activityRates: DestinationChoiceModel.ActivityRates = loadActivityRates(
    beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.intercept_file_path
  )

  val (activityVOTs, activityDurations) = loadActivityParams(
    beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.activity_file_path
  )

  def generateActivityRates(
    activityFileContents: java.util.Iterator[java.lang.String]
  ): Option[DestinationChoiceModel.ActivityRates] = {
    //val out = Map[String, scala.collection.mutable.Map[Int, Double]]
    Try {
      val rows = activityFileContents.asScala
      val headers = rows.next.split(",").map(_.trim).drop(1)
      val out = collection.mutable.Map() ++ headers.map { actType =>
        actType -> Map[Int, Double]()
      }.toMap
      while (rows.hasNext) {
        val row = rows.next.split(",").map(_.trim)
        val hourInd = row.head.toInt
        row.drop(1).zip(headers).foreach {
          case (rate, actType) => out(actType) += (hourInd -> rate.toDouble)
        }
      }
      out.toMap
    } match {
      case Success(out) => Some(out)
      case Failure(f) =>
        println(f)
        None
      case _ => None
    }
  }

  def loadActivityRates(
    activityRateFilePath: String
  ): DestinationChoiceModel.ActivityRates = {
    Try {
      IOUtils.getBufferedReader(activityRateFilePath)
    } match {
      case Success(reader) =>
        val result: Option[DestinationChoiceModel.ActivityRates] = generateActivityRates(reader.lines.iterator)
        result match {
          case Some(params) => params
          case _            => DefaultActivityRates
        }
      case Failure(e) =>
        println(e)
        DefaultActivityRates
    }
  }

  def generateActivityParams(
    activityFileContents: java.util.Iterator[java.lang.String]
  ): Option[(DestinationChoiceModel.ActivityVOTs, DestinationChoiceModel.ActivityDurations)] = {
    //val out = Map[String, scala.collection.mutable.Map[Int, Double]]
    val rows = activityFileContents.asScala
    val headers = rows.next.split(",").map(_.trim).drop(1)
    var VOTs: Option[DestinationChoiceModel.ActivityVOTs] = None
    var Durations: Option[DestinationChoiceModel.ActivityDurations] = None
    while (rows.hasNext) {
      val row = rows.next.split(",").map(_.trim)
      val rowId = row.head
      val paramValues = row
        .drop(1)
        .zip(headers)
        .map {
          case (param, actType) =>
            actType -> param.toDouble
        }
        .toMap
      rowId match {
        case "VOT"               => VOTs = Some(paramValues)
        case "DurationInSeconds" => Durations = Some(paramValues)
        case _                   =>
      }
    }
    (VOTs, Durations) match {
      case (Some(validVOT), Some(validDuration)) => Some((validVOT, validDuration))
      case _                                     => None
    }
  }

  def loadActivityParams(
    activityParamFilePath: String
  ): (DestinationChoiceModel.ActivityVOTs, DestinationChoiceModel.ActivityDurations) = {
    Try {
      IOUtils.getBufferedReader(activityParamFilePath)
    } match {
      case Success(reader) =>
        val result: Option[(DestinationChoiceModel.ActivityVOTs, DestinationChoiceModel.ActivityDurations)] =
          generateActivityParams(reader.lines.iterator)
        result match {
          case Some(params) => params
          case _            => (DefaultActivityVOTs, DefaultActivityDurations)
        }
      case Failure(e) =>
        println(e)
        (DefaultActivityVOTs, DefaultActivityDurations)
    }
  }

  def getActivityUtility(
    activity: Activity,
    attributesOfIndividual: AttributesOfIndividual
  ): Double = {
    val (actStart, actEnd) = getRealStartEndTime(activity)
    val actDuration = actEnd - actStart
    val activityValueOfTime =
    attributesOfIndividual.getVOT(actDuration / 3600) * activityVOTs.getOrElse(activity.getType, 1.0D)
    val activityIntercept = activityRates
      .getOrElse(activity.getType, Map[Int, Double]())
      .getOrElse(secondsToIndex(actStart), 0D)
    val tripIntercept = activity.getType.toLowerCase match {
      case "home" | "work" => 0D
      case _               => beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.additional_trip_utility
    }
    activityValueOfTime + activityIntercept + tripIntercept
  }

  private def getRealStartEndTime(
    activity: Activity
  ): (Double, Double) = {
    val start = if (activity.getStartTime > 0) { activity.getStartTime } else { 0 }
    val end = if (activity.getEndTime > 0) { activity.getEndTime } else { 3600 * 24 }
    (start, end)
  }

  private def secondsToIndex(time: Double): Int = {
    (time / 3600).toInt
  }

}
