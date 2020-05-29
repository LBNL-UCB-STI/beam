package beam.utils

import java.time.temporal.ChronoUnit
import java.time.{LocalDate, LocalDateTime, ZonedDateTime}

import beam.sim.BeamScenario

/**
  * BEAM
  */
case class DateUtils(localBaseDateTime: LocalDateTime, zonedBaseDateTime: ZonedDateTime) {
  val localBaseDate: LocalDate = localBaseDateTime.toLocalDate

  def toBaseMidnightSeconds(time: ZonedDateTime, hasTransit: Boolean): Long = {
    if (hasTransit) {
      ChronoUnit.SECONDS.between(zonedBaseDateTime, time)
    } else {
      ChronoUnit.SECONDS.between(localBaseDateTime, time)
    }
  }
}

object DateUtils {

  def getEndOfTime(beamConfig: beam.sim.config.BeamConfig): Int = {
    val timeAr = beamConfig.beam.agentsim.endTime.split(":")
    timeAr(0).toInt * 3600 + timeAr(1).toInt * 60 + timeAr(2).toInt
  }
}
