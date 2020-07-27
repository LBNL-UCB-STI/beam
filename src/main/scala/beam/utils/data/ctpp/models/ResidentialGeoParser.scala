package beam.utils.data.ctpp.models

import scala.util.{Failure, Success, Try}

object ResidentialGeoParser {
  val markerLength: Int = "C01nnUS".length

  def parse(input: String): Try[String] = {
    val fromTo = input.substring(markerLength)
    if (input.startsWith("C02nnUS")) {
      // C02	CTPP - State	st	C02nnUSss
      val geoId = fromTo.substring(0, "ss".length)
      Success(geoId)
    } else if (input.startsWith("C0300US")) {
      // C03	CTPP - State-County	st/cty	C0300USssccc
      val geoId = fromTo.substring(0, "ssccc".length)
      Success(geoId)
    } else if (input.startsWith("C0400US")) {
      // C04	CTPP - State-County-County Subdivision	st/cty/mcd	C0400USsscccmmmmm
      val geoId = fromTo.substring(0, "sscccmmmmm".length)
      Success(geoId)
    } else if (input.startsWith("C0400US")) {
      // C04	CTPP - State-County-County Subdivision	st/cty/mcd	C0400USsscccmmmmm
      val geoId = fromTo.substring(0, "sscccmmmmm".length)
      Success(geoId)
    } else if (input.startsWith("C0500US")) {
      // C05	CTPP - State-Place	st/pl	C0500USssppppp
      val geoId = fromTo.substring(0, "ssppppp".length)
      Success(geoId)
    } else if (input.startsWith("C0700US")) {
      // C07	CTPP - Metropolitan Statistical Area	cbsa	C0700USbbbbb
      val geoId = fromTo.substring(0, "bbbbb".length)
      Success(geoId)
    } else if (input.startsWith("C0800US")) {
      // C08	CTPP - Metropolitan Statistical Area-State-Principal City	cbsa/st/pl	C0800USbbbbbssppppp
      val geoId = fromTo.substring(0, "bbbbbssppppp".length)
      Success(geoId)
    } else if (input.startsWith("C0900US")) {
      // C09	CTPP - State-Public Use Microdata Sample Area (PUMA)	st/puma5	C0900USssuuuuu
      val geoId = fromTo.substring(0, "ssuuuuu".length)
      Success(geoId)
    } else if (input.startsWith("C1100US")) {
      // C11	CTPP-  State-County-Tract	st/cty/tr	C1100USssccctttttt
      val geoId = fromTo.substring(0, "ssccctttttt".length)
      Success(geoId)
    } else if (input.startsWith("C1200US")) {
      // C12	CTPP-  MPO-TAD	mpo/tad	C1200USoooooooodddddddd
      val geoId = fromTo.substring(0, "oooooooodddddddd".length)
      Success(geoId)
    } else if (input.startsWith("C1300US")) {
      // C13	CTPP-  State-County-TAZ	st/cty/taz	C1300USssccczzzzzzzz
      val geoId = fromTo.substring(0, "ssccczzzzzzzz".length)
      Success(geoId)
    }
    else {
      Failure(new IllegalStateException(s"Don't know how to handle GeoLevel'${fromTo}', input: '${input}'"))
    }
  }

}
