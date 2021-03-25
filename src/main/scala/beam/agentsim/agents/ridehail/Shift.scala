package beam.agentsim.agents.ridehail

import beam.sim.common.Range
import org.matsim.api.core.v01.Coord

/**
  * Holds the data that defines a driver shift.
  *
  * @param range a Range defining the start and end tick of the shift
  * @param startLocation an optional coordinate for the starting location of the shift
  */
case class Shift(range: Range, startLocation: Option[Coord]) {
  override def toString(): String = {
    val locationStr = startLocation
      .map { location =>
        s"[${location.getX}-${location.getY}]"
      }
      .getOrElse("")
    s"$locationStr${range.toString}"
  }
}

object Shift {

  def apply(shiftStr: String) = {
    if (shiftStr.charAt(0).equals('|')) {
      val coordAndRange = shiftStr.split('|')
      if (coordAndRange.size != 3) {
        throw new NumberFormatException(
          s"Shift string malformed, if string begins with | then expecting a second | to enclose the coordinate portion of the shift, but instead found: $shiftStr"
        )
      }
      val startAndEnd = coordAndRange.tail.head.split("-").map(_.toDouble)
      new Shift(Range(coordAndRange.last), Some(new Coord(startAndEnd.head, startAndEnd.last)))
    } else {
      new Shift(Range(shiftStr), None)
    }
  }
}
