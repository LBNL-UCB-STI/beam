package beam.utils.data.ctpp.models

import scala.util.{Failure, Success, Try}

object ResidenceGeoParser {
  val markerLength: Int = "C0300US".length

  def parse(input: String): Try[String] = {
    if (input.startsWith("C02")) {
      //      C02	CTPP - State	st	C02nnUSss
      Success(input.substring("C02nnUS".length))
    } else if (input.startsWith("C1300US")) {
      //      C13	CTPP-  State-County-TAZ	st/cty/taz	C1300USssccczzzzzzzz
      val geoId = input.substring("C1300US".length)
      Success(geoId)
    } else {
      Failure(new IllegalStateException(s"Don't know how to handle GeoLevel input: '${input}'"))
    }
  }
}
