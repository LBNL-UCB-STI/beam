package beam.router.r5

import beam.router.BeamTravelTime
import org.matsim.core.router.util.TravelTime

import java.util.concurrent.ThreadLocalRandom

class CarWeightCalculator(workerParams: R5Parameters, travelTimeNoiseFraction: Double = 0d) {
  private val networkHelper = workerParams.networkHelper
  private val transportNetwork = workerParams.transportNetwork

  val maxFreeSpeed: Double = networkHelper.allLinks.map(_.getFreespeed).max / 0.621371 // Convert kph to mph
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
    edgeLength: Double = -1
  ): Double = {
    val link = networkHelper.getLinkUnsafe(linkId)
    assert(link != null)

    val lengthM =
      if (edgeLength > 0) edgeLength
      else transportNetwork.streetLayer.edgeStore.lengths_mm.get(linkId / 2) / 1000.0

    //  Ensure speeds are sane (no divide by zero, no negatives)
    val safeMaxSpeed = Math.max(maxSpeed, minSpeed) // At least as fast as minSpeed

    // STEP 2: Compute bounds with safe values
    // maxTravelTime = slowest (largest denominator = smallest divisor)
    // minTravelTime = fastest (smallest denominator = largest divisor)
    val maxTravelTime = lengthM / minSpeed
    val minTravelTime = lengthM / safeMaxSpeed

    // Bounds are now guaranteed: 0 <= minTravelTime <= maxTravelTime

    // Get travel time with existing logic
    val physSimTravelTime = travelTime match {
      case beamTT: BeamTravelTime =>
        beamTT.getLinkTravelTime(linkId, time, lengthM)
      case _ =>
        val link = networkHelper.getLinkUnsafe(linkId)
        if (link == null) {
          lengthM / safeMaxSpeed
        } else {
          travelTime.getLinkTravelTime(link, time, null, null)
        }
    }

    // Apply noise if needed
    val physSimTravelTimeWithNoise =
      if (travelTimeNoiseFraction > 0d && shouldAddNoise) {
        physSimTravelTime * ThreadLocalRandom.current().nextDouble(noiseLowerBound, noiseUpperBound)
      } else {
        physSimTravelTime
      }

    // STEP 3: Clamp to valid range (fast path: already in range)
    val clampedTime = if (physSimTravelTimeWithNoise <= maxTravelTime) {
      if (physSimTravelTimeWithNoise >= minTravelTime) {
        physSimTravelTimeWithNoise // Common case: already in range
      } else {
        minTravelTime
      }
    } else {
      maxTravelTime
    }

    // Should be redundant (minTravelTime >= 0) but costs ~1 nanosecond
    Math.max(clampedTime, 0.0)
  }
}
