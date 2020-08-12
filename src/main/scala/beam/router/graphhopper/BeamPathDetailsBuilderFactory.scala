package beam.router.graphhopper

import java.util

import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.details.{PathDetailsBuilder, PathDetailsBuilderFactory}

class BeamPathDetailsBuilderFactory extends PathDetailsBuilderFactory {
  override def createPathDetailsBuilders(
    requestedPathDetails: util.List[String],
    evl: EncodedValueLookup,
    weighting: Weighting
  ): util.List[PathDetailsBuilder] = {
    val builders = super.createPathDetailsBuilders(requestedPathDetails, evl, weighting)
    builders.add(new OriginalIdDetails())
    builders
  }
}
