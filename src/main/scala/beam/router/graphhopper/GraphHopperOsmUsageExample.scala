package beam.router.graphhopper

import com.graphhopper.GHRequest
import com.graphhopper.routing.util.AllEdgesIterator
import com.graphhopper.routing.util.parsers.{DefaultTagParserFactory, TagParser, TagParserFactory}
import com.graphhopper.util.PMap

import scala.collection.JavaConverters._

object GraphHopperOsmUsageExample {

  def main(args: Array[String]): Unit = {
    val tagParserFactory: TagParserFactory = new TagParserFactory {
      val default: TagParserFactory = new DefaultTagParserFactory()

      override def create(name: String, configuration: PMap): TagParser = {
        if (name == "way_id") new WayIdParser()
        else default.create(name, configuration)
      }
    }
    val gh = GraphHopperWrapper.fromOsm("""test/input/sf-light/r5/sflight_muni.osm.pbf""", Some(tagParserFactory))

    val edgeIdToWayIdConvertor = new EdgeIdToWayIdConvertor(gh.getEncodingManager)
    val it: AllEdgesIterator = gh.getGraphHopperStorage.getAllEdges
    while (it.next()) {
      val wayId = edgeIdToWayIdConvertor.getWayId(it)
      println(s"${it.getName}: ${wayId}")
    }

    val ghRequest = new GHRequest(37.781534, -122.495603, 37.725107, -122.38193)
      .setProfile("car")

    val resp = gh.route(ghRequest)
    if (resp.hasErrors) {
      println(s"Response contains errors: ${resp.getErrors.asScala.toList}")
    }

    gh.route(ghRequest).getAll.asScala.foreach { path =>
      println(s"getDistance: ${path.getDistance}")
      println(s"getPoints: ${path.getPoints}")
      println(s"getTime: ${path.getTime / 1000}")
    }
  }
}
