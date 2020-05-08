package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.collection.mutable

/**
  * Not thread-safe
  * @author Dmitry Openkov
  */
class VolumeCalculator {
  val linkToVolume = mutable.Map.empty[Id[Link], Int]

  def addVolume(linkId: Id[Link], v: Int): Unit = linkToVolume.update(linkId, linkToVolume.getOrElse(linkId, 0) + v)

  def getVolume(linkId: Id[Link]): Int = linkToVolume.getOrElse(linkId, 0)
}
