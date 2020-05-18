package beam.physsim.cch

import java.io.File

import com.vividsolutions.jts.geom.Coordinate
import org.scalatest.{FlatSpec, Matchers}

class RoutingFrameworkTravelTimeCalculatorSpec extends FlatSpec with Matchers {
  private val routingFrameworkGraph = new RoutingFrameworkGraphReaderImpl()

//  "RoutingFramework graph reader" must "correctly read generated graph" in {
//    val graphFile = new File(getClass.getResource("/files/graph.gr.bin").getFile)
//
//    val graph = routingFrameworkGraph.read(graphFile)
//
//    graph shouldBe expectedGraph
//  }

}
