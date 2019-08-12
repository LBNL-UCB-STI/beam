package beam.side.speed.model

import java.time.DayOfWeek

import beam.side.speed.parser.Median

import scala.math.{max, min}
import scala.util.Try

case class WeightedHour(weight: Int, hour: UberHourSpeed)

sealed trait Rule extends (() => (Int, Float))

class NightRule(v1: UberHourSpeed) extends Rule {
  def apply: (Int, Float) = (2, v1.speedMax * 2)
}

class DayRule(v1: UberHourSpeed) extends Rule {
  def apply: (Int, Float) = (1, v1.speedMax)
}

object Rule {

  def apply(hour: UberHourSpeed): Rule = hour.hour match {
    case h if h < 5 => new NightRule(hour)
    case _          => new DayRule(hour)
  }
}

sealed trait WayFilter[T <: FilterDTO, E] {
  def filter(filterOption: E, waySpeed: Map[DayOfWeek, UberDaySpeed]): T

  protected def parseHours(hours: Seq[UberHourSpeed]): WaySpeed = {
    val speedAvg =
      Try(hours.map(_.speedAvg).sum / hours.size).toOption.filter(_ =>
        hours.nonEmpty)
    val devMax = Try(hours.map(_.maxDev).max).toOption
    val speedMedian = Option(hours.map(_.speedMedian).toArray)
      .filter(_.nonEmpty)
      .map(Median.findMedian)
    WaySpeed(speedMedian, speedAvg, devMax)
  }
}

object WayFilter {
  implicit val allHoursDaysEventAction: WayFilter[AllHoursDaysDTO, Unit] =
    new WayFilter[AllHoursDaysDTO, Unit] {
      override def filter(
          filterOption: Unit,
          waySpeed: Map[DayOfWeek, UberDaySpeed]): AllHoursDaysDTO = {
        val WaySpeed(speedMedian, speedAvg, devMax) = parseHours(
          waySpeed.values.flatMap(_.hours).toSeq)
        AllHoursDaysDTO(speedMedian, speedAvg, devMax)
      }
    }

  implicit val allHoursWeightedEventAction
    : WayFilter[AllHoursWeightedDTO, Unit] =
    new WayFilter[AllHoursWeightedDTO, Unit] {

      override def filter(
          filterOption: Unit,
          waySpeed: Map[DayOfWeek, UberDaySpeed]): AllHoursWeightedDTO = {
        val hours =
          waySpeed.values.flatMap(_.hours).map(s => Rule(s)).map(_.apply)
        val speedAvg =
          Try(hours.map(_._2).sum / hours.map(_._1).sum).toOption.filter(_ =>
            hours.nonEmpty)
        val speedMedian =
          Option(hours.flatMap(w => Seq.fill(w._1)(w._2 / w._1)).toArray)
            .filter(_.nonEmpty)
            .map(Median.findMedian)
        AllHoursWeightedDTO(speedMedian, speedAvg)
      }
    }

  implicit val weekDayEventAction: WayFilter[WeekDayDTO, DayOfWeek] =
    new WayFilter[WeekDayDTO, DayOfWeek] {
      override def filter(
          filterOption: DayOfWeek,
          waySpeed: Map[DayOfWeek, UberDaySpeed]): WeekDayDTO = {
        val WaySpeed(speedMedian, speedAvg, devMax) = parseHours(
          waySpeed.get(filterOption).map(_.hours).getOrElse(Seq()))
        WeekDayDTO(speedMedian, speedAvg, devMax)
      }
    }

  implicit val hourEventAction: WayFilter[HourDTO, Int] =
    new WayFilter[HourDTO, Int] {
      override def filter(filterOption: Int,
                          waySpeed: Map[DayOfWeek, UberDaySpeed]): HourDTO = {
        val WaySpeed(speedMedian, speedAvg, devMax) = parseHours(
          waySpeed.values.flatMap(_.hours).filter(_.hour == filterOption).toSeq
        )
        HourDTO(speedMedian, speedAvg, devMax)
      }
    }

  implicit val hourRangeEventAction: WayFilter[HourRangeDTO, (Int, Int)] =
    new WayFilter[HourRangeDTO, (Int, Int)] {
      override def filter(
          filterOption: (Int, Int),
          waySpeed: Map[DayOfWeek, UberDaySpeed]): HourRangeDTO = {
        val WaySpeed(speedMedian, speedAvg, devMax) = parseHours(
          waySpeed.values
            .flatMap(_.hours)
            .filter(
              h =>
                (min(filterOption._1, filterOption._2) to max(
                  filterOption._1,
                  filterOption._2)).toList.contains(h.hour)
            )
            .toSeq
        )
        HourRangeDTO(speedMedian, speedAvg, devMax)
      }
    }

  implicit val weekDayHourEventAction
    : WayFilter[WeekDayHourDTO, (DayOfWeek, Int)] =
    new WayFilter[WeekDayHourDTO, (DayOfWeek, Int)] {
      override def filter(
          filterOption: (DayOfWeek, Int),
          waySpeed: Map[DayOfWeek, UberDaySpeed]): WeekDayHourDTO = {
        val WaySpeed(speedMedian, speedAvg, devMax) = parseHours(
          waySpeed
            .get(filterOption._1)
            .map(_.hours)
            .getOrElse(Seq())
            .filter(_.hour == filterOption._2)
        )
        WeekDayHourDTO(speedMedian, speedAvg, devMax)
      }
    }

  implicit val maxHoursPointsEventAction
    : WayFilter[MaxHourPointsDTO, MaxHourPointFiltered] =
    new WayFilter[MaxHourPointsDTO, MaxHourPointFiltered] {
      override def filter(
          filterOption: MaxHourPointFiltered,
          waySpeed: Map[DayOfWeek, UberDaySpeed]
      ): MaxHourPointsDTO = {
        val hoursRange = if (filterOption.from > filterOption.to) {
          (0 to 23).toList.diff((filterOption.to to filterOption.from).toList)
        } else {
          (0 to 23).toList
            .intersect((filterOption.from to filterOption.to).toList)
        }
        val points = waySpeed.values
          .flatMap(_.hours)
          .foldLeft(0)(
            (acc, h) =>
              Option(hoursRange.contains(h.hour))
                .filter(identity)
                .fold(acc)(_ => acc + 1))
        val speedMax = waySpeed.values.flatMap(_.hours).map(_.speedMax).max
        MaxHourPointsDTO(speedMax, points)
      }
    }

  implicit val beamLengthWeightedEventAction: WayFilter[BeamLengthDTO, Unit] =
    new WayFilter[BeamLengthDTO, Unit] {
      override def filter(
          filterOption: Unit,
          waySpeed: Map[DayOfWeek, UberDaySpeed]): BeamLengthDTO = {
        val lenghts = waySpeed.values
          .flatMap(_.hours)
          .map(s => (s.speedMax * s.maxDev) -> s.maxDev)
        val speedAvg =
          Try(lenghts.map(_._1).sum / lenghts.map(_._2).sum).toOption
            .filter(_ => lenghts.nonEmpty)
        BeamLengthDTO(speedAvg)
      }
    }
}
