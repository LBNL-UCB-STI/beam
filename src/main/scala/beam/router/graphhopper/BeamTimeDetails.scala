package beam.router.graphhopper

import com.graphhopper.routing.weighting.Weighting

class BeamTimeDetails(weighting: Weighting)
    extends AbstractBeamTimeDetails(weighting, BeamTimeDetails.BEAM_TIME, reverse = false)

object BeamTimeDetails {
  val BEAM_TIME = "beam_time"
}
