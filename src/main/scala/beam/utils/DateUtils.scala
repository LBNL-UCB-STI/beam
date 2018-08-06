package beam.utils

import java.time.temporal.ChronoUnit
import java.time.{LocalDate, LocalDateTime, ZonedDateTime}

/**
  * BEAM
  */
case class DateUtils(localBaseDateTime: LocalDateTime,
                     zonedBaseDateTime: ZonedDateTime) {
  val localBaseDate: LocalDate = localBaseDateTime.toLocalDate

  def toBaseMidnightSeconds(time: ZonedDateTime, hasTransit: Boolean): Long = {
    if (hasTransit) {
      ChronoUnit.SECONDS.between(zonedBaseDateTime, time)
    } else {
      ChronoUnit.SECONDS.between(localBaseDateTime, time)
    }
  }
}
