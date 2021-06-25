package beam.router.skim

import enumeratum._
import scala.collection.immutable

sealed abstract class ActivitySimTimeBin(val hours: List[Int]) extends EnumEntry

object ActivitySimTimeBin extends Enum[ActivitySimTimeBin] {
  val values: immutable.IndexedSeq[ActivitySimTimeBin] = findValues

  //  from activity sim documentation:
  //    EA - early AM, 3 am to 6 am
  //    AM - peak period, 6 am to 10 am,
  //    MD - midday period, 10 am to 3 pm,
  //    PM - peak period, 3 pm to 7 pm,
  //    EV - evening, 7 pm to 3 am the next day

  case object EA extends ActivitySimTimeBin(hours = (3 until 6).toList)
  case object AM extends ActivitySimTimeBin(hours = (6 until 10).toList)
  case object MD extends ActivitySimTimeBin(hours = (10 until 15).toList)
  case object PM extends ActivitySimTimeBin(hours = (15 until 19).toList)
  case object EV extends ActivitySimTimeBin(hours = (0 until 3).toList ++ (19 until 24).toList)

  def toTimeBin(hour: Int): ActivitySimTimeBin = {
    val actualHour = hour % 24
    actualHour match {
      case d if d < 3  => EV
      case d if d < 6  => EA
      case d if d < 10 => AM
      case d if d < 15 => MD
      case d if d < 19 => PM
      case _           => EV
    }
  }
}
