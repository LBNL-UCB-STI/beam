package beam.router.skim

import enumeratum._
import scala.collection.immutable

sealed abstract class ActivitySimTimeBin(val hours: List[Int], val shortName: String) extends EnumEntry {
  override def toString: String = shortName
}

object ActivitySimTimeBin extends Enum[ActivitySimTimeBin] {
  val values: immutable.IndexedSeq[ActivitySimTimeBin] = findValues

  //  from activity sim documentation:
  //    EA - early AM, 3 am to 6 am
  //    AM - peak period, 6 am to 10 am,
  //    MD - midday period, 10 am to 3 pm,
  //    PM - peak period, 3 pm to 7 pm,
  //    EV - evening, 7 pm to 3 am the next day

  case object EARLY_AM extends ActivitySimTimeBin(hours = (3 until 6).toList, shortName = "EA")
  case object AM_PEAK extends ActivitySimTimeBin(hours = (6 until 10).toList, shortName = "AM")
  case object MIDDAY extends ActivitySimTimeBin(hours = (10 until 15).toList, shortName = "MD")
  case object PM_PEAK extends ActivitySimTimeBin(hours = (15 until 19).toList, shortName = "PM")
  case object EVENING extends ActivitySimTimeBin(hours = (0 until 3).toList ++ (19 until 24).toList, shortName = "EV")

  def toTimeBin(hour: Int): ActivitySimTimeBin = {
    val actualHour = hour % 24
    actualHour match {
      case d if d < 3  => EVENING
      case d if d < 6  => EARLY_AM
      case d if d < 10 => AM_PEAK
      case d if d < 15 => MIDDAY
      case d if d < 19 => PM_PEAK
      case _           => EVENING
    }
  }
}
