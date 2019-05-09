package beam.utils.traveltime

import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Link

import scala.collection.mutable
import scala.collection.JavaConverters._

object NetworkUtil extends LazyLogging {

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
        links.foreach {
          case (id, lnk) =>
            if (!visited.contains(lnk)) {
              queue.enqueue(lnk)
              visited.add(lnk)
            }
        }
      }
    }
    numberOfHops
  }

  def getLinks(src: Link, level: Int, direction: Direction): scala.collection.Map[Int, Array[Link]] = {
    val visited = new mutable.HashSet[Link]()
    val queue = new mutable.Queue[(Link, Int)]()
    queue.enqueue((src, 1))
    visited.add(src)

    val result = mutable.Map[Int, mutable.Set[Link]]()
    // logger.info(s"src: $src")
    while (queue.nonEmpty) {
      val (link, lvl) = queue.dequeue()
      if (lvl <= level) {
        val links = direction match {
          case Direction.In =>
            link.getToNode.getOutLinks.values().asScala.toArray
          case Direction.Out =>
            link.getFromNode.getInLinks.values().asScala.toArray
        }
        // logger.info(s"$lvl. Link is ${link.getId}")
        // logger.info(s"To: ${links.map(_.getId).toList}")
        result.get(lvl) match {
          case Some(xs) => links.foreach(xs.add)
          case None =>
            val hs = mutable.Set[Link]()
            links.foreach(hs.add)
            result.put(lvl, hs)
        }
        links.foreach { lnk =>
          if (!visited.contains(lnk)) {
            queue.enqueue((lnk, lvl + 1))
            visited.add(lnk)
          }
        }
      }
    }
    result(1).remove(src)
    result.map { case (lvl, set) => lvl -> set.toArray }
  }

  def getLinks0(
    link: Link,
    currentLevel: Int,
    level: Int,
    direction: Direction,
    levelToLinks: Map[Int, Set[Link]]
  ): Map[Int, Set[Link]] = {
    level match {
      case 0 =>
        levelToLinks
      case _ =>
        val links = direction match {
          case Direction.Out =>
            link.getToNode.getOutLinks.values().asScala
          case Direction.In =>
            link.getFromNode.getInLinks.values().asScala
        }
        val out = levelToLinks.updated(currentLevel, links.toSet)
        val maps = links.flatMap(getLinks0(_, currentLevel + 1, level - 1, direction, out))
        maps.foldLeft(Map[Int, Set[Link]]()) {
          case (acc, (k, v)) =>
            val r1 = acc.getOrElse(k, Set.empty)
            acc.updated(k, r1 ++ v)
        }
    }
  }
}
