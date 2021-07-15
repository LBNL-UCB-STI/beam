package beam.utils.traveltime

import org.matsim.api.core.v01.network.Link

import scala.collection.mutable

import scala.collection.JavaConverters._

object NetworkUtil {
  sealed trait Direction

  object Direction {
    case object Out extends Direction
    case object In extends Direction
  }

  def numOfHops(src: Link, dst: Link, direction: Direction): Int = {
    val visited = new mutable.HashSet[Link]()
    val queue = new mutable.Queue[Link]()
    queue.enqueue(src)
    visited.add(src)

    var link: Link = null
    var shouldStop: Boolean = false
    var numberOfHops: Int = 0
    while (queue.nonEmpty && !shouldStop) {
      link = queue.dequeue()
      visited.add(link)
      if (link == dst) {
        shouldStop = true
      } else {
        numberOfHops += 1
        val links = direction match {
          case Direction.In =>
            link.getToNode.getOutLinks.asScala
          case Direction.Out =>
            link.getFromNode.getInLinks.asScala
        }
        links.foreach { case (_, lnk) =>
          if (!visited.contains(lnk)) {
            queue.enqueue(lnk)
            visited.add(lnk)
          }
        }
      }
    }
    numberOfHops
  }

  def getLinks(link: Link, level: Int, direction: Direction): Array[Link] = {
    val links = getLinks0(link, level, direction, Array())
    links.filter(x => x != link)
  }

  def getLinks0(link: Link, level: Int, direction: Direction, arr: Array[Link]): Array[Link] = {
    level match {
      case 0 =>
        arr.distinct
      case _ =>
        val links = direction match {
          case Direction.Out =>
            link.getToNode.getOutLinks.values().asScala
          case Direction.In =>
            link.getFromNode.getInLinks.values().asScala
        }
        val out = arr ++ links
        links.flatMap(getLinks0(_, level - 1, direction, out)).toArray.distinct
    }
  }
}
