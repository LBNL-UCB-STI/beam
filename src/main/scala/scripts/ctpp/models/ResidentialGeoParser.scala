package scripts.ctpp.models

import scala.util.{Failure, Success, Try}

object ResidentialGeoParser {
  val markerLength: Int = "C01nnUS".length

  val inputMapping = Map(
    "C02nnUS" -> "ss".length, // C02 CTPP - State st C02nnUSss
    "C0300US" -> "ssccc".length, // C03 CTPP - State-County st/cty C0300USssccc
    "C0400US" -> "sscccmmmmm".length, // C04	CTPP - State-County-County Subdivision	st/cty/mcd	C0400USsscccmmmmm
    "C0500US" -> "ssppppp".length, // C05	CTPP - State-Place	st/pl	C0500USssppppp
    "C0700US" -> "bbbbb".length, // C07	CTPP - Metropolitan Statistical Area	cbsa	C0700USbbbbb
    "C0800US" -> "bbbbbssppppp".length, // C08	CTPP - Metropolitan Statistical Area-State-Principal City	cbsa/st/pl	C0800USbbbbbssppppp
    "C0900US" -> "ssuuuuu".length, // C09	CTPP - State-Public Use Microdata Sample Area (PUMA)	st/puma5	C0900USssuuuuu
    "C1100US" -> "ssccctttttt".length, // C11	CTPP-  State-County-Tract	st/cty/tr	C1100USssccctttttt,
    "C1200US" -> "oooooooodddddddd".length, // C12	CTPP-  MPO-TAD	mpo/tad	C1200USoooooooodddddddd
    "C1300US" -> "ssccczzzzzzzz".length // C13 CTPP- State-County-TAZ st/cty/taz C1300USssccczzzzzzzz
  )

  def parse(input: String): Try[String] = {
    val fromTo = input.substring(markerLength)
    inputMapping
      .collectFirst {
        case (prefix, length) if input.startsWith(prefix, length) =>
          Success(fromTo.substring(0, length))
      }
      .getOrElse(Failure(new IllegalStateException(s"Don't know how to handle GeoLevel'${fromTo}', input: '${input}'")))
  }
}
