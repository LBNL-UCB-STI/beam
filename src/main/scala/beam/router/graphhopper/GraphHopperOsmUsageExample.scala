package beam.router.graphhopper

import com.graphhopper.GHRequest

import scala.collection.JavaConverters._

object GraphHopperOsmUsageExample {

  def main(args: Array[String]): Unit = {
    val gh = GraphHopperWrapper.fromOsm("""test/input/sf-light/r5/sflight_muni.osm.pbf""")

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
