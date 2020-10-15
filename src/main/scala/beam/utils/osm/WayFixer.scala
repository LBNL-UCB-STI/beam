package beam.utils.osm

import beam.sim.config.BeamConfig
import com.conveyal.osmlib.Way
import com.typesafe.scalalogging.LazyLogging

import scala.Function.tupled
import scala.collection.JavaConverters._
import scala.util.Try

object WayFixer extends LazyLogging {
  val HIGHWAY_TAG: String = "highway"
  val LANES_TAG: String = "lanes"
  val MAXSPEED_TAG: String = "maxspeed"

  def fix(ways: java.util.Map[java.lang.Long, Way], beamConfig: BeamConfig): Unit = {
    val waysMap = ways.asScala.map { case (k, v) => (Long2long(k), v) }

    val nFixedHighways = waysMap.count(tupled(fixHighwayType))
    val nFixedLanes = waysMap.count(tupled(fixLanes))

    logger.info(s"Fixed highway types in $nFixedHighways from ${ways.size}")
    logger.info(s"Fixed lanes in $nFixedLanes from ${ways.size}")

    val maxSpeedConfig = beamConfig.beam.physsim.network.maxSpeedInference
    if (maxSpeedConfig.enabled) {
      val maxSpeedByRoadType = OsmSpeedConverter.avgMaxSpeedByRoadType(waysMap.values.toList, maxSpeedConfig.`type`)
      val nDerivedMaxSpeeds = waysMap.count(tupled(deriveMaxSpeedFromRoadType(maxSpeedByRoadType)))
      logger.info(s"Derived maxSpeed in $nDerivedMaxSpeeds from ${ways.size} using mapping:\n$maxSpeedByRoadType")
    }
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

  def deriveMaxSpeedFromRoadType(maxSpeedByRoadType: Map[String, Double])(osmId: Long, way: Way): Boolean = {
    getDerivedByHighway(maxSpeedByRoadType, way).exists { maxSpeed =>
      replace(osmId, way, MAXSPEED_TAG, maxSpeed.toString)
      true
    }
  }

  private[osm] def getDerivedByHighway(maxSpeedByRoadType: Map[String, Double], way: Way): Option[Double] = {
    (Option(way.getTag(HIGHWAY_TAG)), Option(way.getTag(MAXSPEED_TAG))) match {
      case (Some(highwayType), None) => maxSpeedByRoadType.get(highwayType)
      case _                         => None
    }
  }

  private[osm] def replace(osmId: Long, way: Way, tagName: String, newValue: String): Unit = {
    val oldValue = way.getTag(tagName)
    val oldTags = way.tags.asScala.filter(tag => tag.key == tagName)
    way.tags.removeAll(oldTags.asJava)
    way.addTag(tagName, newValue)
    logger.debug(s"Fixed $tagName tag for OSM[$osmId]. Tag value was '$oldValue', become '$newValue'")
  }

  private[osm] def getFixedLanes(osmId: Long, way: Way): Option[Int] = {
    Option(way.getTag(LANES_TAG)).flatMap { rawLanes =>
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
    }
  }

  private[osm] def getFixedHighwayType(osmId: Long, way: Way): Option[String] = {
    Option(way.getTag(HIGHWAY_TAG)).flatMap { rawHighwayType =>
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
