package beam.router.r5

import org.matsim.core.router.util.TravelTime
import java.util.concurrent.ThreadLocalRandom

class CarWeightCalculator(workerParams: R5Parameters, travelTimeNoiseFraction: Double = 0d) {
  private val networkHelper = workerParams.networkHelper
  private val transportNetwork = workerParams.transportNetwork

  val maxFreeSpeed: Double = networkHelper.allLinks.map(_.getFreespeed).max
  private val minSpeed = workerParams.beamConfig.beam.physsim.minCarSpeedInMetersPerSecond

  // Pre-compute noise bounds for faster generation
  private val noiseLowerBound = 1 - travelTimeNoiseFraction
  private val noiseUpperBound = 1 + travelTimeNoiseFraction

  def calcTravelTime(linkId: Int, travelTime: TravelTime, time: Double): Double = {
    calcTravelTime(linkId, travelTime, maxFreeSpeed, time, shouldAddNoise = false)
  }

  def calcTravelTime(
    linkId: Int,
    travelTime: TravelTime,
    maxSpeed: Double,
    time: Double,
    shouldAddNoise: Boolean,
    edgeLength: Double = -1 // Allow passing pre-computed edge length
  ): Double = {
    val link = networkHelper.getLinkUnsafe(linkId)
    assert(link != null)
    // Use provided edge length if available, otherwise look it up
    val lengthM =
      if (edgeLength > 0) edgeLength
      else {
        transportNetwork.streetLayer.edgeStore.getCursor(linkId).getLengthM
      }

    // Pre-compute these values once
    val maxTravelTime = lengthM / minSpeed
    val minTravelTime = lengthM / maxSpeed

    val physSimTravelTime = travelTime.getLinkTravelTime(link, time, null, null)

    // Generate noise only if needed
    val physSimTravelTimeWithNoise =
      if (travelTimeNoiseFraction > 0d && shouldAddNoise) {
        // Generate a value between 0 and 1, scale it to the noise range, then shift it
        physSimTravelTime * ThreadLocalRandom.current().nextDouble(noiseLowerBound, noiseUpperBound)
      } else {
        physSimTravelTime
      }

    // Use Math.min/max for cleaner clamping
    Math.min(Math.max(physSimTravelTimeWithNoise, minTravelTime), maxTravelTime)
  }
}
