package beam.physsim.bprsim

import beam.physsim.jdeqsim.cacc.CACCSettings
import org.matsim.api.core.v01.network.Link

/**
  * @param simEndTime phys simulation end time
  * @param travelTimeFunction function that calculates travel time at a given time, link, CACC share and volume
  * @author Dmitry Openkov
  */
case class BPRSimConfig(
  simEndTime: Double,
  numberOfClusters: Int,
  syncInterval: Int,
  flowCapacityFactor: Double,
  inFlowAggregationTimeWindow: Int,
  travelTimeFunction: (Double, Link, Double, Double) => Double,
  caccSettings: Option[CACCSettings],
)

case class BPRSimParams(config: BPRSimConfig, volumeCalculator: VolumeCalculator)
