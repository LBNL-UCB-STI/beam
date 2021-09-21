package beam.physsim

import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.collection.mutable

class PickUpDropOffHolder(linkToPickUpsDropOffs: mutable.HashMap[Int, LinkPickUpsDropOffs], beamConfig: BeamConfig)
    extends LazyLogging {

  val linkIdToPickUpsDropOffs: Map[Id[Link], LinkPickUpsDropOffs] = toLinkIdToPickUpsDropOffsMap

  val secondsForPickUpPropOffToAffectTravelTime: Int =
    beamConfig.beam.physsim.pickUpDropOffAnalysis.secondsFromPickUpPropOffToAffectTravelTime

  val additionalTravelTimeMultiplier: Double =
    beamConfig.beam.physsim.pickUpDropOffAnalysis.additionalTravelTimeMultiplier

  var linkTravelTimeAnalyzed: Long = 0
  var linkTravelTimeAffected: Long = 0

  private val totalPickUpsDropOffs = linkIdToPickUpsDropOffs.map { case (_, linkPD) =>
    linkPD.timeToPickUps.times.size + linkPD.timeToDropOffs.times.size
  }.sum
  logger.info(
    s"Holder created with total $totalPickUpsDropOffs pick ups and drop offs for ${linkIdToPickUpsDropOffs.size} links."
  )

  private def toLinkIdToPickUpsDropOffsMap: Map[Id[Link], LinkPickUpsDropOffs] = {
    linkToPickUpsDropOffs.map { case (strLinkId, pickUpsDropOffs) =>
      (Id.createLinkId(strLinkId), pickUpsDropOffs)
    }.toMap
  }

  def getAdditionalLinkTravelTime(link: Link, simulationTime: Double): Double = {
    val linkId: Id[Link] = link.getId

    val additionalTravelTime = linkIdToPickUpsDropOffs.get(linkId) match {
      case Some(pickUpsDropOffs) => pickUpsDropOffsToTravelTime(simulationTime, link, pickUpsDropOffs)
      case None                  => 0.0
    }

    if (additionalTravelTime > 0.0) {
      linkTravelTimeAffected += 1
    }

    linkTravelTimeAnalyzed += 1
    additionalTravelTime
  }

  protected def pickUpsDropOffsToTravelTime(
    simulationTime: Double,
    link: Link,
    pickUpsDropOffs: LinkPickUpsDropOffs
  ): Double = {

    val originalTravelTime = link.getLength / link.getFreespeed
    val pickups =
      pickUpsDropOffs.timeToPickUps.getClosestValue(simulationTime, secondsForPickUpPropOffToAffectTravelTime) match {
        case Some(cnt) => cnt
        case None      => 0
      }
    val dropoffs =
      pickUpsDropOffs.timeToDropOffs.getClosestValue(simulationTime, secondsForPickUpPropOffToAffectTravelTime) match {
        case Some(cnt) => cnt
        case None      => 0
      }

    originalTravelTime * additionalTravelTimeMultiplier * (pickups + dropoffs) / link.getNumberOfLanes
  }

  def additionalLinkTravelTimeCalculationFunction(): AdditionalLinkTravelTimeCalculationFunction =
    new AdditionalLinkTravelTimeCalculationFunctionImplementation(getAdditionalLinkTravelTime)
}

class AdditionalLinkTravelTimeCalculationFunctionImplementation(
  calcAdditionalLinkTravelTime: (Link, Double) => Double
) extends AdditionalLinkTravelTimeCalculationFunction {

  override def getAdditionalLinkTravelTime(link: Link, simulationTime: Double): Double =
    calcAdditionalLinkTravelTime(link, simulationTime)
}
