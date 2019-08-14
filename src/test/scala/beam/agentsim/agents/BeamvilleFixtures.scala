package beam.agentsim.agents

import com.vividsolutions.jts.geom.Envelope

trait BeamvilleFixtures {
  // FIXME What should be actual bounding box?
  val boundingBox: Envelope = new Envelope(167000, 833000, 0, 10000000)
}
