package beam.side.speed.parser
import java.time.DayOfWeek

import beam.side.speed.model._

sealed trait WayFilter[T <: FilterDTO, E] {
  def filter(filterOption: E, waySpeed: Map[DayOfWeek, UberDaySpeed]): WaySpeed

  protected def parseHours(hours: Seq[UberHourSpeed]): WaySpeed = {
    val speedAvg = hours.map(_.speedMean).sum / hours.size
    val devMax = hours.map(_.maxDev).max
    val speedMean = Median.findMedian(hours.map(_.speedMean).toArray)
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
