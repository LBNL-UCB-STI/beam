package beam.utils.osm

import beam.utils.osm.WayFixer.{HIGHWAY_TAG, MAXSPEED_TAG}
import com.conveyal.osmlib.Way

object OsmSpeedConverter {
  // All possible units are enumerated at https://wiki.openstreetmap.org/wiki/Map_Features/Units#Speed
  private val MphRegex = """([0-9]*\.?[0-9]+)\s+mph""".r
  private val KnotsRegex = """([0-9]*\.?[0-9]+)\s+knots""".r
  private val KmphRegex = """([0-9]*\.?[0-9]+)\s*(km\/h|kph|kmph)?""".r

  final case class HighwayTypeSpeed(highwayType: String, maxSpeedKph: BigDecimal)

  private def mph2kmph(mph: BigDecimal) = mph * 1.60934
  private def knots2kmph(knots: BigDecimal) = knots * 1.852

  private def osmSpeedUnit2kmph: PartialFunction[String, Option[BigDecimal]] = {
    case MphRegex(maxSpeedMph)     => Some(mph2kmph(BigDecimal(maxSpeedMph)))
    case KnotsRegex(maxSpeedKnots) => Some(knots2kmph(BigDecimal(maxSpeedKnots)))
    case KmphRegex(maxSpeed, _)    => Some(BigDecimal(maxSpeed))
    case _                         => None
  }

  def averageMaxSpeedByRoadTypes(ways: List[Way]): Map[String, Double] =
    ways
      .flatMap { way =>
        for {
          highwayType <- Option(way.getTag(HIGHWAY_TAG))
          maxSpeed    <- Option(way.getTag(MAXSPEED_TAG))
          maxSpeedKph <- osmSpeedUnit2kmph(maxSpeed)
        } yield HighwayTypeSpeed(highwayType, maxSpeedKph)
      }
      .groupBy(_.highwayType)
      .mapValues(allSpeeds => allSpeeds.map(_.maxSpeedKph).sum / allSpeeds.size)
      .mapValues(_.doubleValue)

}
