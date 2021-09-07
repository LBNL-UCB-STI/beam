package beam.utils.osm

import beam.utils.osm.WayFixer.{HIGHWAY_TAG, MAXSPEED_TAG}
import com.conveyal.osmlib.Way

object OsmSpeedConverter {
  // All possible units are enumerated at https://wiki.openstreetmap.org/wiki/Map_Features/Units#Speed
  private val MphRegex = """([0-9]*\.?[0-9]+)\s+mph""".r
  private val KnotsRegex = """([0-9]*\.?[0-9]+)\s+knots""".r
  private val KmphRegex = """([0-9]*\.?[0-9]+)\s*(km\/h|kph|kmph)?""".r

  final case class HighwayTypeSpeed(highwayType: String, maxSpeedKph: BigDecimal)

  private def mph2kmph(mph: BigDecimal) = mph * 1.609344
  private def knots2kmph(knots: BigDecimal) = knots * 1.852

  private[osm] def osmSpeedUnit2kmph: PartialFunction[String, Option[BigDecimal]] = {
    case MphRegex(maxSpeedMph)     => Some(mph2kmph(BigDecimal(maxSpeedMph)))
    case KnotsRegex(maxSpeedKnots) => Some(knots2kmph(BigDecimal(maxSpeedKnots)))
    case KmphRegex(maxSpeed, _)    => Some(BigDecimal(maxSpeed))
    case _                         => None
  }

  def avgMaxSpeedByRoadType(ways: List[Way], inferenceType: String): Map[String, Double] =
    ways
      .flatMap { way =>
        for {
          highwayType <- Option(way.getTag(HIGHWAY_TAG))
          maxSpeed    <- Option(way.getTag(MAXSPEED_TAG))
          maxSpeedKph <- osmSpeedUnit2kmph(maxSpeed)
        } yield HighwayTypeSpeed(highwayType, maxSpeedKph)
      }
      .groupBy(_.highwayType)
      .mapValues(values => averageValue(values.map(_.maxSpeedKph), inferenceType))
      .mapValues(_.doubleValue)

  private[osm] def averageValue(values: List[BigDecimal], inferenceType: String): BigDecimal = inferenceType match {
    case "MEAN"   => values.mean
    case "MEDIAN" => values.median
    case x        => throw new IllegalArgumentException(s"Unsupported maxSpeed inference type: $x")
  }

  private final implicit class RichBigDecimalList(values: List[BigDecimal]) {
    private val size = values.size

    def mean: BigDecimal = values.sum / size

    def median: BigDecimal = {
      val sortedValues = values.sorted
      if (size % 2 != 0) {
        sortedValues(size / 2)
      } else {
        val (left, right) = sortedValues.splitAt(size / 2)
        (left.last + right.head) / 2
      }
    }
  }
}
