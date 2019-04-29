package beam.utils.osm

import com.conveyal.osmlib.Way
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.util.Try

object WayFixer extends LazyLogging {
  val HIGHWAY_TAG: String = "highway"
  val LANES_TAG: String = "lanes"
  val MAXSPEED_TAG: String = "maxspeed"

  def fix(ways: java.util.Map[java.lang.Long, Way]): Unit = {
    val nFixedHighways = ways.asScala.count {
      case (osmId, way) =>
        fixHighwayType(osmId, way)
    }
    val nFixedLanes = ways.asScala.count {
      case (osmId, way) =>
        fixLanes(osmId, way)
    }
    logger.info(s"Fixed highway types in $nFixedHighways from ${ways.size}")
    logger.info(s"Fixed lanes in $nFixedLanes from ${ways.size}")
  }

  def fixHighwayType(osmId: Long, way: Way): Boolean = {
    getFixedHighwayType(osmId, way).exists { fixedHighwayType =>
      replace(osmId, way, HIGHWAY_TAG, fixedHighwayType)
      true
    }
  }

  def fixLanes(osmId: Long, way: Way): Boolean = {
    getFixedLanes(osmId, way).exists { fixedLane =>
      replace(osmId, way, LANES_TAG, fixedLane.toString)
      true
    }
  }

//  def fixSpeed(osmId: Long, way: Way): Boolean = {
//    getFixedSpeed(osmId, way).exists { maxSpeed =>
//      replace(osmId, way, MAXSPEED_TAG, maxSpeed.toString)
//      true
//    }
//  }

//  private[osm] def getFixedSpeed(osmId: Long, way: Way): Option[Double] = {
//    Option(way.getTag(MAXSPEED_TAG)) match {
//      case Some(rawMaxSpeed) =>
//        if (rawMaxSpeed.startsWith("[")) {
//          val maxSpeedStr = split(rawMaxSpeed)
//          if (maxSpeedStr.isEmpty) {
//            logger.warn(s"Could not split maxspeed from '$rawMaxSpeed'. OSM[$osmId]")
//            None
//          } else {
//            val lanes = maxSpeedStr.flatMap { x =>
//              Try(x.toInt).toOption
//            }
//            val avgLanes = if (lanes.isEmpty) 1 else lanes.sum.toDouble / lanes.length
//            Some(avgLanes.toInt)
//          }
//        } else {
//          None
//        }
//      case None =>
//        logger.warn(s"Could not find tag[$LANES_TAG] from OSM[$osmId]")
//        None
//    }
//  }

//  private[osm] def getFixedSpeed()

  private[osm] def replace(osmId: Long, way: Way, tagName: String, newValue: String): Unit = {
    val oldValue = way.getTag(tagName)
    val oldTags = way.tags.asScala.filter(tag => tag.key == tagName)
    way.tags.removeAll(oldTags.asJava)
    way.addTag(tagName, newValue)
    logger.debug(s"Fixed $tagName tag for OSM[$osmId]. Tag value was '$oldValue', become '$newValue'")
  }

  private[osm] def getFixedLanes(osmId: Long, way: Way): Option[Int] = {
    Option(way.getTag(LANES_TAG)) match {
      case Some(rawLanes) =>
        if (rawLanes.startsWith("[")) {
          val lanesAsStr = split(rawLanes)
          if (lanesAsStr.isEmpty) {
            logger.warn(s"Could not split lane from '$rawLanes'. OSM[$osmId]")
            None
          } else {
            val lanes = lanesAsStr.flatMap { x =>
              Try(x.toInt).toOption
            }
            val avgLanes = if (lanes.isEmpty) 1 else lanes.sum.toDouble / lanes.length
            Some(avgLanes.toInt)
          }
        } else {
          None
        }
      case None =>
        logger.warn(s"Could not find tag[$LANES_TAG] from OSM[$osmId] and Way[$way]")
        None
    }
  }

  private[osm] def getFixedHighwayType(osmId: Long, way: Way): Option[String] = {
    Option(way.getTag(HIGHWAY_TAG)) match {
      case Some(rawHighwayType) =>
        if (rawHighwayType.startsWith("[")) {
          val highwayTypes = split(rawHighwayType)
          if (highwayTypes.isEmpty) {
            logger.warn(s"Could not split highway from '$rawHighwayType'. OSM[$osmId] and Way[$way]")
            None
          } else {
            val firstNonLink = highwayTypes.find(ht => !ht.contains("_link"))
            val fixedHighwayType = firstNonLink.getOrElse(highwayTypes.head)
            Some(fixedHighwayType)
          }
        } else {
          None
        }
      case None =>
        logger.warn(s"Could not find tag[$HIGHWAY_TAG] from OSM[$osmId] and Way[$way]")
        None
    }
  }

  private def split(str: String): Array[String] = {
    str
      .replace("[", "")
      .replace("]", "")
      .replace("'", "")
      .split(",")
      .map(_.trim)
  }
}
