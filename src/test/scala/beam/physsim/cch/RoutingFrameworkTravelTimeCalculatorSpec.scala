package beam.physsim.cch

import java.io.File

import beam.sim.BeamServices
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

class RoutingFrameworkTravelTimeCalculatorSpec extends FlatSpec with Matchers with MockitoSugar {
  private val wrapper = mock[RoutingFrameworkWrapper]
  private val infoHolder = mock[OsmInfoHolder]
  private val services = mock[BeamServices]
  private val calculator = new RoutingFrameworkTravelTimeCalculator(
    services,
    infoHolder,
    wrapper
  )


  "RoutingFramework graph reader" must "correctly read generated graph" in {
//    calculator.generateLink2TravelTimes()
//    val graphFile = new File(getClass.getResource("/files/graph.gr.bin").getFile)
//
//    val graph = routingFrameworkGraph.read(graphFile)
//
//    graph shouldBe expectedGraph
  }

}
