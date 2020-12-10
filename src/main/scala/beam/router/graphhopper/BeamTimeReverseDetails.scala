package beam.router.graphhopper

import com.graphhopper.routing.weighting.Weighting

class BeamTimeReverseDetails(weighting: Weighting)
    extends AbstractBeamTimeDetails(weighting, BeamTimeReverseDetails.BEAM_REVERSE_TIME, reverse = true)

object BeamTimeReverseDetails {
  val BEAM_REVERSE_TIME = "beam_time_reverse"
}
