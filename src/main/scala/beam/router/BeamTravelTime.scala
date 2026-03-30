package beam.router

import beam.utils.NetworkHelper
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle

/**
  * Extension of MATSim's TravelTime interface that adds methods for more efficient
  * travel time lookups using integer link IDs directly.
  */
trait BeamTravelTime extends TravelTime {

  /**
    * Get travel time using integer link ID directly.
    * This avoids the overhead of Link object lookups and string parsing.
    */
  def getLinkTravelTime(linkId: Int, time: Double): Double

  /**
    * Optional method that can also accept pre-computed link length for further optimization.
    */
  def getLinkTravelTime(linkId: Int, time: Double, linkLengthMeters: Double): Double =
    getLinkTravelTime(linkId, time)
}

/**
  * Free flow travel time implementation optimized for direct integer ID access.
  */
class BeamFreeFlowTravelTime(networkHelper: NetworkHelper) extends BeamTravelTime {

  // Cache link lengths for faster access
  private val linkLengths: Array[Double] = {
    val maxLinkId = networkHelper.allLinks.map(link => Integer.parseInt(link.getId.toString)).max

    val lengths = new Array[Double](maxLinkId + 1)
    networkHelper.allLinks.foreach { link =>
      val id = Integer.parseInt(link.getId.toString)
      lengths(id) = link.getLength
    }
    lengths
  }

  // Cache link free speeds for faster access
  private val linkInverseFreeSpeeds: Array[Double] = {
    val maxLinkId = networkHelper.allLinks.map(link => Integer.parseInt(link.getId.toString)).max

    val speeds = new Array[Double](maxLinkId + 1)
    networkHelper.allLinks.foreach { link =>
      val id = Integer.parseInt(link.getId.toString)
      speeds(id) = 1.0 / link.getFreespeed
    }
    speeds
  }

  // Original MATSim interface method
  override def getLinkTravelTime(link: Link, time: Double, person: Person, vehicle: Vehicle): Double = {
    link.getLength / link.getFreespeed
  }

  // Optimized method using integer ID
  override def getLinkTravelTime(linkId: Int, time: Double): Double = {
    linkLengths(linkId) * linkInverseFreeSpeeds(linkId)
  }

  // Further optimized method with pre-computed length
  override def getLinkTravelTime(linkId: Int, time: Double, linkLengthMeters: Double): Double = {
    linkLengthMeters * linkInverseFreeSpeeds(linkId)
  }
}
