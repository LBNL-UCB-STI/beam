package scripts

import java.io.File
import scala.collection.mutable
import scala.xml.transform.{RewriteRule, RuleTransformer}
import scala.xml.{Elem, Node, PrettyPrinter, XML}

case class LatLon(lat: Double, lon: Double)

// Usage:
// ./gradlew :execute \
//     -PmaxRAM=10 \
//     -PmainClass=scripts.ConsolidateOSMNodes \
//     -PappArgs="['links0.osm','links_consolidated.osm']"
object ConsolidateOSMNodes {

  private val locationToIds: mutable.Map[LatLon, mutable.Seq[Long]] =
    mutable.Map.empty.withDefaultValue(mutable.Seq.empty)
  private val idToLocation: mutable.Map[Long, LatLon] = mutable.Map.empty

  private val replaceRedundantId = new RewriteRule {

    override def transform(node: Node): Seq[Node] = {
      node match {
        case nd: Elem if nd.label == "nd" =>
          val id = (nd \ "@ref").text.toInt
          val latLon = idToLocation(id)
          val head :: tail = locationToIds(latLon).toList
          if (tail.contains(id)) {
            val metaData =
              scala.xml.Attribute(key = "ref", value = scala.xml.Text(head.toString), next = scala.xml.Null)
            nd % metaData
          } else nd
        case n => n
      }
    }
  }

  private val removeNode = new RewriteRule {

    override def transform(node: Node): Seq[Node] = {
      node match {
        case node: Elem if node.label == "node" =>
          val id = (node \ "@id").text.toInt
          val latLon = LatLon(
            (node \ "@lat").text.toDouble,
            (node \ "@lon").text.toDouble
          )
          val _ :: tail = locationToIds(latLon).toList
          if (tail.contains(id)) Seq.empty
          else node
        case n => n
      }
    }
  }

  private def populateState(xml: Node): Unit = {
    for {
      osm  <- xml \\ "osm"
      node <- osm \\ "node"
    } {
      val id = (node \ "@id").text.toLong
      val latLon = LatLon(
        (node \ "@lat").text.toDouble,
        (node \ "@lon").text.toDouble
      )
      idToLocation.update(id, latLon)
      val seq = locationToIds(latLon)
      locationToIds.update(latLon, seq :+ id)
    }
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      println("""
          |Usage:
          |./gradlew :execute \
          |    -PmaxRAM=10 \
          |    -PmainClass=scripts.ConsolidateOSMNodes \
          |    -PappArgs="['links0.osm','links_consolidated.osm']"
          |""".stripMargin)
      System.exit(1)
    }

    val osmFile = new File(args(0))
    println("Loading xml..")
    val xml = XML.loadFile(osmFile)
    populateState(xml)

    val transformer = new RuleTransformer(
      replaceRedundantId,
      removeNode
    )

    println("Consolidating network nodes..")
    val output = {
      val root = transformer.transform(xml)
      val printer = new PrettyPrinter(120, 2, true)
      XML.loadString(printer.format(root.head))
    }

    println("Writing xml..")
    XML.save(args(1), output, "UTF-8", xmlDecl = true, null)
  }
}
