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
  def filter(filterOption: E, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed

  protected def parseHours(hours: Seq[UberHourSpeed]): WaySpeed = {
    val speedAvg = Try(hours.map(_.speedAvg).sum / hours.size).toOption.filter(_ => hours.nonEmpty)
    val devMax = Try(hours.map(_.maxDev).max).toOption
    val speedMedian = Option(hours.map(_.speedMedian).toArray).filter(_.nonEmpty).map(Median.findMedian)
    WaySpeed(speedMedian, speedAvg, devMax)
  }
}

object WayFilter {
  implicit val allHoursDaysEventAction: WayFilter[AllHoursDaysDTO, Unit] = new WayFilter[AllHoursDaysDTO, Unit] {
    override def filter(filterOption: Unit, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
      parseHours(waySpeed.values.flatMap(_.hours).toSeq)
  }

  implicit val allHoursWeightedEventAction: WayFilter[AllHoursWeightedDTO, Unit] =
    new WayFilter[AllHoursWeightedDTO, Unit] {

      override def filter(filterOption: Unit, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed = {
        val hours = waySpeed.values.flatMap(_.hours).map(s => Rule(s)).map(_.apply)
        val speedAvg = Try(hours.map(_._2).sum / hours.map(_._1).sum).toOption.filter(_ => hours.nonEmpty)
        val speedMedian =
          Option(hours.flatMap(w => Seq.fill(w._1)(w._2 / w._1)).toArray).filter(_.nonEmpty).map(Median.findMedian)
        WaySpeed(speedMedian, speedAvg, None)
      }
    }

  implicit val weekDayEventAction: WayFilter[WeekDayDTO, DayOfWeek] = new WayFilter[WeekDayDTO, DayOfWeek] {
    override def filter(filterOption: DayOfWeek, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
      parseHours(waySpeed.get(filterOption).map(_.hours).getOrElse(Seq()))
  }

  implicit val hourEventAction: WayFilter[HourDTO, Int] = new WayFilter[HourDTO, Int] {
    override def filter(filterOption: Int, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
      parseHours(waySpeed.values.flatMap(_.hours).filter(_.hour == filterOption).toSeq)
  }

  implicit val hourRangeEventAction: WayFilter[HourRangeDTO, (Int, Int)] = new WayFilter[HourRangeDTO, (Int, Int)] {
    override def filter(filterOption: (Int, Int), waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
      parseHours(
        waySpeed.values
          .flatMap(_.hours)
          .filter(
            h =>
              (min(filterOption._1, filterOption._2) to max(filterOption._1, filterOption._2)).toList.contains(h.hour)
          )
          .toSeq
      )
  }

  implicit val weekDayHourEventAction: WayFilter[WeekDayHourDTO, (DayOfWeek, Int)] =
    new WayFilter[WeekDayHourDTO, (DayOfWeek, Int)] {
      override def filter(filterOption: (DayOfWeek, Int), waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
        parseHours(waySpeed.get(filterOption._1).map(_.hours).getOrElse(Seq()).filter(_.hour == filterOption._2))
    }
}
