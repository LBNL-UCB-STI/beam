package beam.physsim.bprsim

import org.matsim.api.core.v01.network.Link

/**
  * @param simEndTime phys simulation end time
  * @param travelTimeFunction function that calculates travel time at a given time, link and volume
  * @author Dmitry Openkov
  */
case class BPRSimConfig(
  simEndTime: Double,
  numberOfClusters: Int,
  syncInterval: Int,
  inFlowAggregationTimeWindow: Int,
  travelTimeFunction: (Double, Link, Double) => Double,
)

case class BPRSimParams(config: BPRSimConfig, volumeCalculator: VolumeCalculator)
