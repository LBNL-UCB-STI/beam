package beam.router.graphhopper

import java.util
import java.util.stream.Collectors

import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.details.{PathDetailsBuilder, PathDetailsBuilderFactory}

class BeamPathDetailsBuilderFactory extends PathDetailsBuilderFactory {
  override def createPathDetailsBuilders(
    requestedPathDetails: util.List[String],
    evl: EncodedValueLookup,
    weighting: Weighting
  ): util.List[PathDetailsBuilder] = {
    val defaultDetailNames = requestedPathDetails
      .stream()
      .filter(it => !BeamPathDetailsBuilderFactory.CUSTOM_DETAILS.contains(it))
      .collect(Collectors.toList())

    val details = super.createPathDetailsBuilders(defaultDetailNames, evl, weighting)
    if (requestedPathDetails.contains(BeamTimeDetails.BEAM_TIME)) {
      details.add(new BeamTimeDetails(weighting))
    }
    if (requestedPathDetails.contains(BeamTimeReverseDetails.BEAM_REVERSE_TIME)) {
      details.add(new BeamTimeReverseDetails(weighting))
    }

    details
  }
}

object BeamPathDetailsBuilderFactory {
  private val CUSTOM_DETAILS = List(BeamTimeDetails.BEAM_TIME, BeamTimeReverseDetails.BEAM_REVERSE_TIME)
}
