package beam.side.speed.model

import java.time.DayOfWeek

import beam.side.speed.parser.Median

import scala.util.Try

sealed trait WayFilter[T <: FilterDTO, E] {
  def filter(filterOption: E, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed

  protected def parseHours(hours: Seq[UberHourSpeed]): WaySpeed = {
    val speedAvg = Try(hours.map(_.speedMedian).sum / hours.size).getOrElse(0f)
    val devMax = Try(hours.map(_.maxDev).max).getOrElse(0f)
    val speedMean = Option(hours.map(_.speedMedian).toArray).filter(_.nonEmpty).map(Median.findMedian).getOrElse(0f)
    WaySpeed(speedMean, speedAvg, devMax)
  }
}

object WayFilter {
  implicit val allHoursDaysEventAction: WayFilter[AllHoursDaysDTO, Unit] = new WayFilter[AllHoursDaysDTO, Unit] {
    override def filter(filterOption: Unit, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
      parseHours(waySpeed.values.flatMap(_.hours).toSeq)
  }

  implicit val weekDayEventAction: WayFilter[WeekDayDTO, DayOfWeek] = new WayFilter[WeekDayDTO, DayOfWeek] {
    override def filter(filterOption: DayOfWeek, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
      parseHours(waySpeed.get(filterOption).map(_.hours).getOrElse(Seq()))
  }

  implicit val hourEventAction: WayFilter[HourDTO, Int] = new WayFilter[HourDTO, Int] {
    override def filter(filterOption: Int, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
      parseHours(waySpeed.values.flatMap(_.hours).filter(_.hour == filterOption).toSeq)
  }

  implicit val weekDayHourEventAction: WayFilter[WeekDayHourDTO, (DayOfWeek, Int)] =
    new WayFilter[WeekDayHourDTO, (DayOfWeek, Int)] {
      override def filter(filterOption: (DayOfWeek, Int), waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed =
        parseHours(waySpeed.get(filterOption._1).map(_.hours).getOrElse(Seq()).filter(_.hour == filterOption._2))
    }
}
