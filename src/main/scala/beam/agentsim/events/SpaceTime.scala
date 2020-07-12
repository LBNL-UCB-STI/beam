package beam.agentsim.events

import beam.utils.MathUtils
import io.circe.{Encoder, Json}
import org.matsim.api.core.v01.Coord

case class SpaceTime(loc: Coord, time: Int) extends Ordered[SpaceTime] {
  override def compare(that: SpaceTime): Int = {
    var r = loc.getX.compare(that.loc.getX)
    if (r != 0) r
    else {
      r = loc.getY.compare(that.loc.getY)
      if (r != 0) r
      else {
        time.compareTo(that.time)
      }
    }
  }
}

object SpaceTime {
  def apply(x: Double, y: Double, time: Int): SpaceTime = SpaceTime(new Coord(x, y), time)

  def apply(tup: (Coord, Int)): SpaceTime = tup match {
    case (c, l) => SpaceTime(c, l)
  }

  implicit val encodeSpaceTime: Encoder[SpaceTime] = (a: SpaceTime) => {
    Json.fromValues(
      Seq[Json](
        Json.fromDoubleOrNull(MathUtils.roundDouble(a.loc.getX, 5)), // TODO: Hardcoded. Should this be configurable?
        Json.fromDoubleOrNull(MathUtils.roundDouble(a.loc.getY, 5)), // TODO: Ditto.
        Json.fromLong(a.time)
      )
    )
  }

  implicit val orderingByTime: Ordering[SpaceTime] = (x: SpaceTime, y: SpaceTime) => {
    x.time.compareTo(y.time)
  }

  val zero: SpaceTime = SpaceTime(0, 0, 0)
}
