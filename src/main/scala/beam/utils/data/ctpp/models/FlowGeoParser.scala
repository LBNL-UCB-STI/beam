package beam.utils.data.ctpp.models

import scala.util.{Failure, Success, Try}

object FlowGeoParser {
  val markerLength: Int = "C5600US".length

  def parse(input: String): Try[(String, String)] = {
    val fromTo = input.substring(markerLength)
    if (input.startsWith("C5600US")) {
      // C56	CTPP Flow- TAZ-to-TAZ	st/cty/taz/st/cty/taz	C5600USssccczzzzzzzzssccczzzzzzzz
      val fromGeoId = fromTo.substring(0, "ssccczzzzzzzz".length)
      val toGeoId = fromTo.substring("ssccczzzzzzzz".length)
      Success((fromGeoId, toGeoId))
    } else {
      Failure(new IllegalStateException(s"Don't know how to handle GeoLevel'${fromTo}', input: '${input}'"))
    }
  }
}
