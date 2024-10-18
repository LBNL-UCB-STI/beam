package beam.physsim.bprsim

import beam.physsim.jdeqsim.cacc.CACCSettings
import beam.utils.metrics.TemporalEventCounter
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

/**
  * @param simEndTime phys simulation end time
  * @param travelTimeFunction function that calculates travel time at a given time, link, CACC share, volume
  *                           and number of double parked vehicles on the link
  * @author Dmitry Openkov
  */
case class BPRSimConfig(
  simEndTime: Double,
  numberOfClusters: Int,
  syncInterval: Int,
  flowCapacityFactor: Double,
  inFlowAggregationTimeWindow: Int,
  travelTimeFunction: (Double, Link, Double, Double, Int) => Double,
  caccSettings: Option[CACCSettings]
)

case class BPRSimParams(
  config: BPRSimConfig,
  volumeCalculator: VolumeCalculator,
  doubleParkingCounter: TemporalEventCounter[Id[Link]]
)
