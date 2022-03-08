package beam.router.r5

import beam.agentsim.agents.vehicles.BeamVehicleType
import org.matsim.core.router.util.TravelTime

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

class CarWeightCalculator(workerParams: R5Parameters, travelTimeNoiseFraction: Double = 0d) {
  private val networkHelper = workerParams.networkHelper
  private val transportNetwork = workerParams.transportNetwork

  val maxFreeSpeed: Double = networkHelper.allLinks.map(_.getFreespeed).max
  private val minSpeed = workerParams.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond

  private val noiseIdx: AtomicInteger = new AtomicInteger(0)

  private val travelTimeNoises: Array[Double] = if (travelTimeNoiseFraction.equals(0d)) {
    Array.empty
  } else {
    Array.fill(1000000) {
      ThreadLocalRandom.current().nextDouble(1 - travelTimeNoiseFraction, 1 + travelTimeNoiseFraction)
    }
  }

  def calcTravelTime(linkId: Int, travelTime: TravelTime, time: Double): Double = {
    calcTravelTime(linkId, travelTime, None, time, shouldAddNoise = false)
  }

  def calcTravelTime(
    linkId: Int,
    travelTime: TravelTime,
    vehicleType: Option[BeamVehicleType],
    time: Double,
    shouldAddNoise: Boolean
  ): Double = {
    val link = networkHelper.getLinkUnsafe(linkId)
    assert(link != null)
    val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
    val maxTravelTime = edge.getLengthM / minSpeed
    val maxSpeed: Double = vehicleType match {
      case Some(vType) => vType.maxVelocity.getOrElse(maxFreeSpeed)
      case None        => maxFreeSpeed
    }

    val minTravelTime = edge.getLengthM / maxSpeed

    val physSimTravelTime = travelTime.getLinkTravelTime(link, time, null, null)
    val physSimTravelTimeWithNoise =
      if (travelTimeNoiseFraction.equals(0d) || !shouldAddNoise) {
        physSimTravelTime
      } else {
        val idx = Math.abs(noiseIdx.getAndIncrement() % travelTimeNoises.length)
        physSimTravelTime * travelTimeNoises(idx)
      }
    val linkTravelTime = Math.max(physSimTravelTimeWithNoise, minTravelTime)
    Math.min(linkTravelTime, maxTravelTime)
  }
}
