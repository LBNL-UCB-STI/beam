package beam.router.graphhopper

import com.graphhopper.GraphHopper
import com.graphhopper.routing.WeightingFactory

class BeamGraphHopper(wayId2TravelTime: Map[Long, Double]) extends GraphHopper {

  override def createWeightingFactory(): WeightingFactory =
    new BeamWeightingFactory(wayId2TravelTime, getGraphHopperStorage, getEncodingManager)
}
