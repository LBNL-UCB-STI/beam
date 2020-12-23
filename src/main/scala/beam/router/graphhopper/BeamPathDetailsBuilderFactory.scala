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
    val customDetails = List(new BeamTimeDetails(weighting), new BeamTimeReverseDetails(weighting))
    val customDetailAliases = customDetails.map(_.getName)
    val details = super.createPathDetailsBuilders(
      requestedPathDetails
        .stream()
        .filter(!customDetailAliases.contains(_))
        .collect(Collectors.toList()),
      evl,
      weighting
    )
    customDetails.foreach(details.add)
    details
  }
}
