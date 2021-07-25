package beam.utils.data.ctpp.models

object FlowGeoParser {
  val markerLength: Int = "C5600US".length

  def parse(input: String): (String, String) = {
    val fromTo = input.substring(markerLength)
    if (input.startsWith("C5600US")) {
      // C56	CTPP Flow- TAZ-to-TAZ	st/cty/taz/st/cty/taz	C5600USssccczzzzzzzzssccczzzzzzzz
      val fromGeoId = fromTo.substring(0, "ssccczzzzzzzz".length)
      val toGeoId = fromTo.substring("ssccczzzzzzzz".length)
      (fromGeoId, toGeoId)
    } else if (input.startsWith("C5400US")) {
      // C54	CTPP Flow- Tract-to-Tract	st/cty/tr/st/cty/tr	C5400USsscccttttttssccctttttt
      val fromGeoId = fromTo.substring(0, "ssccctttttt".length)
      val toGeoId = fromTo.substring("ssccctttttt".length)
      (fromGeoId, toGeoId)
    } else if (input.startsWith("C4900US")) {
      // C49	CTPP Flow - PUMA- to -POW PUMA	st/puma5/st/powpuma	C4900USssuuuuussuuuuu
      val fromGeoId = fromTo.substring(0, "ssuuuuu".length)
      val toGeoId = fromTo.substring("ssuuuuu".length)
      (fromGeoId, toGeoId)
    } else if (input.startsWith("C5000US")) {
      // C50	CTPP Flow - Place-to-County	st/pl/st/cty	C5000USsspppppssccc
      val fromGeoId = fromTo.substring(0, "ssppppp".length)
      val toGeoId = fromTo.substring("ssppppp".length)
      (fromGeoId, toGeoId)
    } else if (input.startsWith("C4300US")) {
      // C43	CTPP Flow - County-to-County	st/cty/st/cty	C4300USsscccssccc
      val fromGeoId = fromTo.substring(0, "ssccc".length)
      val toGeoId = fromTo.substring("ssccc".length)
      (fromGeoId, toGeoId)
    } else if (input.startsWith("C4200US")) {
      // C42	CTPP Flow - State-to-State	st/st	C4200USssss
      val fromGeoId = fromTo.substring(0, "ss".length)
      val toGeoId = fromTo.substring("ss".length)
      (fromGeoId, toGeoId)
    } else if (input.startsWith("C5500US")) {
      // C55	CTPP Flow- TAD-to-TAD	mpo/tad/mpo/tad	C5500USooooooooddddddddoooooooodddddddd
      val fromGeoId = fromTo.substring(0, "oooooooodddddddd".length)
      val toGeoId = fromTo.substring("oooooooodddddddd".length)
      (fromGeoId, toGeoId)
    } else {
      throw new IllegalStateException(s"Don't know how to handle GeoLevel'$fromTo', input: '$input'")
    }
  }

}
