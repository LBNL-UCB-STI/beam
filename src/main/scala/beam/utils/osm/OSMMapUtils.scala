package beam.utils.osm

import beam.utils.csv.CsvWriter
import com.conveyal.osmlib.{OSM, Way}

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.util.Random

object OSMMapUtils {

  def readMap(filePath: String): OSM = {
    val osm = new OSM(null)
    osm.readFromFile(filePath)
    osm
  }

  def showInfo(osm: OSM): Unit = {
    val totalWays = osm.ways.size()

    println(s"count of nodes is: ${osm.nodes.size()}")
    println(s"count of ways is: $totalWays")
  }

  def writeTagToWay(osm: OSM, outputCSVFilePath: String): Unit = {
    val tagValToWay = mutable.Map.empty[String, Int]
    def plusTagWay(tagVal: String): Unit = {
      tagValToWay.get(tagVal) match {
        case Some(count) => tagValToWay(tagVal) = count + 1
        case None        => tagValToWay(tagVal) = 1
      }
    }

    osm.ways.asScala.foreach {
      case (_, way) =>
        if (way.tags == null) plusTagWay("empty-tag")
        else way.tags.asScala.foreach(osmTag => plusTagWay(s"${osmTag.key}+${osmTag.value}"))
    }

    val csvWriter = new CsvWriter(
      outputCSVFilePath,
      Vector(
        "tag+value",
        "wayCnt",
      )
    )

    tagValToWay.toSeq.sortBy(_._2).foreach {
      case (tag, cnt) => csvWriter.writeRow(IndexedSeq("\"" + tag.replace(',', '.') + "\"", cnt))
    }

    csvWriter.close()
  }

  def copyWithChosenWays(osm: OSM, isChosen: Way => Boolean): OSM = {
    val selectedNodes = mutable.HashSet.empty[Long]

    val copy = new OSM(null)
    copy.writeBegin()

    val secondsSinceEpoch: Long = System.currentTimeMillis / 1000
    copy.setReplicationTimestamp(secondsSinceEpoch)

    osm.ways.asScala.foreach {
      case (wayId, way) if isChosen(way) =>
        copy.writeWay(wayId, way)
        way.nodes.foreach(nodeId => selectedNodes.add(nodeId))
      case _ =>
    }

    osm.nodes.asScala.foreach {
      case (nodeId, node) if selectedNodes.contains(nodeId) => copy.writeNode(nodeId, node)
      case _                                                =>
    }

    osm.relations.asScala.foreach {
      case (relationId, relation) => copy.writeRelation(relationId, relation)
    }

    copy
  }

  def main(args: Array[String]): Unit = {

    //    showInfo("/mnt/data/work/beam/beam/test/input/detroit/r5-light/detroit-cut.osm.pbf")

    val mapPath = "/mnt/data/work/beam/detroit-scenario-data/detroit-big.osm.pbf"
    val cutMapPath = "/mnt/data/work/beam/detroit-scenario-data/detroit-big-without-residential.osm.pbf"
    val map = readMap(mapPath)
    showInfo(map)

    val random = new Random()
    val map_cut = copyWithChosenWays(map, _ => random.nextDouble() < 0.4)
    showInfo(map_cut)
    map_cut.writeToFile(cutMapPath)

//    showInfo("/mnt/data/work/beam/beam/test/input/detroit/r5/detroit-big.osm.pbf")
//    showInfo("/mnt/data/work/beam/beam/test/input/texas/r5-prod/texas-six-counties-simplified.osm.pbf")

//    for ((_, relation) <- osm.relations.asScala) {
//      if (relation.tags != null)
//        for (tag <- relation.tags.asScala) {
//          tags.get(tag.key) match {
//            case Some(vals) => vals += tag.value
//            case None       => tags(tag.key) = mutable.Set(tag.value)
//          }
//        }
//
////      for (member <- relation.members.asScala) {
////                if (member.`type` eq OSMEntity.Type.NODE) assertTrue(osm.relationsByNode.contains(Fun.t2(member.id, id)))
////                else if (member.`type` eq OSMEntity.Type.WAY) assertTrue(osm.relationsByWay.contains(Fun.t2(member.id, id)))
////                else if (member.`type` eq OSMEntity.Type.RELATION) assertTrue(osm.relationsByRelation.contains(Fun.t2(member.id, id)))
////      }
//    }
//    println(tags.size)
  }
}
