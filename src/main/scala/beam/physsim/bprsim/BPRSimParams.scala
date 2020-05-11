package beam.physsim.bprsim

import org.matsim.api.core.v01.network.Link

/**
  * @param simEndTime phys simulation end time
  * @param travelTime function that calculates travel time at a given time, link and volume
  * @author Dmitry Openkov
  */
case class BPRSimConfig(simEndTime: Double, numberOfClusters: Int, travelTime: (Double, Link, Int) => Double)

case class BPRSimParams(config: BPRSimConfig, volumeCalculator: VolumeCalculator)
