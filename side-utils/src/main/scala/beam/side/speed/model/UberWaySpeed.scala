package beam.side.speed.model

import java.time.DayOfWeek

case class UberHourSpeed(hour: Int, speedMean: Float, speedAvg: Float, maxDev: Float)

case class UberDaySpeed(weekDay: DayOfWeek, hours: Seq[UberHourSpeed])

case class UberWaySpeed(segmentId: String, week: Seq[UberDaySpeed])

object UberWaySpeed {
  def apply(segmentId: String, week: Seq[UberDaySpeed]): UberWaySpeed = new UberWaySpeed(segmentId, week)
}
