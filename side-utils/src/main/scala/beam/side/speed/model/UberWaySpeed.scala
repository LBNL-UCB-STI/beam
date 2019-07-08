package beam.side.speed.model

import java.time.DayOfWeek

case class UberHourSpeed(hour: Int, speedMedian: Float, speedAvg: Float, maxDev: Float)

case class UberDaySpeed(weekDay: DayOfWeek, hours: Seq[UberHourSpeed])

class UberWaySpeed(week: Seq[UberDaySpeed]) {

  lazy private val dictionary: Map[DayOfWeek, UberDaySpeed] = week.map(e => e.weekDay -> e).toMap

  def waySpeed[T <: FilterEventAction](filterOption: T#Filtered)(
    implicit filter: WayFilter[T#FilterEvent, T#Filtered]
  ): WaySpeed = {
    filter.filter(filterOption, dictionary)
  }

  override def toString = s"UberWaySpeed($week)"
}

object UberWaySpeed {
  def apply(week: Seq[UberDaySpeed]): UberWaySpeed = new UberWaySpeed(week)
}
