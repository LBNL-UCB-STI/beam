package beam.agentsim.agents.ridehail

import java.util

import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.router.util.TravelTime

class RideHailNetworkAPI {

  var r5Network: Option[TransportNetwork] = None
  var matsimNetwork: Option[Network] = None
  var maybeTravelTime: Option[TravelTime] = None

  def setR5Network(transportNetwork: TransportNetwork): Unit = {
    this.r5Network = Some(transportNetwork)
  }

  def setMATSimNetwork(network: Network): Unit = {
    this.matsimNetwork = Some(network)
  }

  def setTravelTime(travelTime: TravelTime): Unit = {
    this.maybeTravelTime = Some(travelTime)
  }

  def getTravelTimeEstimate(time: Double, linkId: Int): Double = {
    maybeTravelTime match {
      case Some(matsimTravelTime) =>
        matsimTravelTime
          .getLinkTravelTime(
            matsimNetwork.get.getLinks.get(Id.createLinkId(linkId)),
            time,
            null,
            null
          )
          .toLong
      case None =>
        val edge = r5Network.get.streetLayer.edgeStore.getCursor(linkId)
        (edge.getLengthM / edge.calculateSpeed(
          new ProfileRequest,
          StreetMode.valueOf(StreetMode.CAR.toString)
        )).toLong
    }
  }

  def getFreeFlowTravelTime(linkId: Int): Option[Double] = {
    getLinks match {
      case Some(links) =>
        Some(links.get(Id.createLinkId(linkId.toString)).getFreespeed)
      case None => None
    }
  }

  // TODO: make integers
  def getLinks: Option[util.Map[Id[Link], _ <: Link]] = {
    matsimNetwork match {
      case Some(network) => Some(network.getLinks)
      case None          => None
    }
  }

  def getFromLinkIds(linkId: Int): Vector[Int] = {
    convertLinkIdsToVector(getMATSimLink(linkId).getFromNode.getInLinks.keySet()) // Id[Link].toString
  }

  def convertLinkIdsToVector(set: util.Set[Id[Link]]): Vector[Int] = {

    val iterator = set.iterator
    var linkIdVector: Vector[Int] = Vector()
    while (iterator.hasNext) {
      val linkId: Id[Link] = iterator.next()
      val _linkId = linkId.toString.toInt
      linkIdVector = linkIdVector :+ _linkId
    }

    linkIdVector
  }

  private def getMATSimLink(linkId: Int): Link = {
    matsimNetwork.get.getLinks.get(Id.createLinkId(linkId))
  }

  def getToLinkIds(linkId: Int): Vector[Int] = {
    convertLinkIdsToVector(getMATSimLink(linkId).getToNode.getOutLinks.keySet()) // Id[Link].toString
  }

  def getLinkCoord(linkId: Int): Coord = {
    matsimNetwork.get.getLinks.get(Id.createLinkId(linkId)).getCoord
  }

  def getFromNodeCoordinate(linkId: Int): Coord = {
    matsimNetwork.get.getLinks.get(Id.createLinkId(linkId)).getFromNode.getCoord
  }

  def getToNodeCoordinate(linkId: Int): Coord = {
    matsimNetwork.get.getLinks.get(Id.createLinkId(linkId)).getToNode.getCoord
  }

  def getClosestLink(coord: Coord): Option[Link] = {
    matsimNetwork match {
      case Some(network) => Some(NetworkUtils.getNearestLink(network, coord));
      case None          => None
    }
  }

}
