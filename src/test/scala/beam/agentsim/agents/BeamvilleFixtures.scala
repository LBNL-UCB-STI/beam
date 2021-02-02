package beam.agentsim.agents

import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Coord

trait BeamvilleFixtures {
  val boundingBoxBuffer = BeamvilleFixtures.boundingBoxBuffer
  val boundingBox: Envelope = BeamvilleFixtures.boundingBox
  val taz1Loc = BeamvilleFixtures.taz1Loc
  val taz2Loc = BeamvilleFixtures.taz2Loc
  val taz3Loc = BeamvilleFixtures.taz3Loc
  val taz4Loc = BeamvilleFixtures.taz4Loc
}

object BeamvilleFixtures {
  val boundingBoxBuffer = 5000.0

  val boundingBox: Envelope = new Envelope(
    166015.9 - boundingBoxBuffer,
    170484.2 + boundingBoxBuffer,
    -5.534138 - boundingBoxBuffer,
    4432.844587 + boundingBoxBuffer
  )
  val taz1Loc = new Coord(167141.3, 1112.351)
  val taz2Loc = new Coord(167141.3, 3326.017)
  val taz3Loc = new Coord(169369.8, 1112.351)
  val taz4Loc = new Coord(169369.8, 3326.017)
}
