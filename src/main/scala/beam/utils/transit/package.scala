package beam.utils

import java.time.LocalTime

package object transit {
  case class FrequencyAdjustment(
    routeId: String,
    tripId: String,
    startTime: LocalTime,
    endTime: LocalTime,
    headwaySecs: Int,
    exactTimes: Option[Int] = None
  )
}
