package beam.physsim.cchRoutingAssignment
import java.io.File

import com.vividsolutions.jts.geom.Coordinate
import org.scalatest.{FlatSpec, Matchers}

class RoutingFrameworkGraphReaderSpec extends FlatSpec with Matchers {
  private val routingFrameworkGraph = new RoutingFrameworkGraphReaderImpl()

  "RoutingFramework graph reader" must "correctly read generated graph" in {
    val basePath = System.getenv("PWD")
    val graphFile = new File(s"$basePath/test/test-resources/beam/router/graph.gr.bin")

    val graph = routingFrameworkGraph.read(graphFile)

    graph shouldBe expectedGraph
  }

  val expectedGraph: RoutingFrameworkGraph = RoutingFrameworkGraph(
    Seq(
      Vertex(43, new Coordinate(0.01995, -5.0E-5)),
      Vertex(42, new Coordinate(0.01995, 5.0E-5)),
      Vertex(52, new Coordinate(0.02005, 0.02995)),
      Vertex(86, new Coordinate(0.03995, 0.04005)),
      Vertex(54, new Coordinate(0.01995, 0.03005)),
      Vertex(79, new Coordinate(0.03995, 0.01995)),
      Vertex(27, new Coordinate(0.00995, 0.00995)),
      Vertex(34, new Coordinate(0.00995, 0.03005)),
      Vertex(75, new Coordinate(0.03995, 0.00995)),
      Vertex(37, new Coordinate(0.01005, 0.04005)),
      Vertex(76, new Coordinate(0.04005, 0.01995)),
      Vertex(25, new Coordinate(0.01005, 0.01005)),
      Vertex(31, new Coordinate(0.00995, 0.01995)),
      Vertex(68, new Coordinate(0.04005, -5.0E-5)),
      Vertex(69, new Coordinate(0.04005, 5.0E-5)),
      Vertex(32, new Coordinate(0.01005, 0.02995)),
      Vertex(26, new Coordinate(0.00995, 0.01005)),
      Vertex(74, new Coordinate(0.03995, 0.01005)),
      Vertex(66, new Coordinate(0.02995, 0.04005)),
      Vertex(33, new Coordinate(0.01005, 0.03005)),
      Vertex(29, new Coordinate(0.01005, 0.02005)),
      Vertex(58, new Coordinate(0.01995, 0.04005)),
      Vertex(24, new Coordinate(0.01005, 0.00995)),
      Vertex(77, new Coordinate(0.04005, 0.02005)),
      Vertex(40, new Coordinate(0.02005, -5.0E-5)),
      Vertex(28, new Coordinate(0.01005, 0.01995)),
      Vertex(41, new Coordinate(0.02005, 5.0E-5)),
      Vertex(35, new Coordinate(0.00995, 0.02995)),
      Vertex(85, new Coordinate(0.04005, 0.04005)),
      Vertex(44, new Coordinate(0.02005, 0.00995)),
      Vertex(14, new Coordinate(-5.0E-5, 0.03005)),
      Vertex(13, new Coordinate(5.0E-5, 0.03005)),
      Vertex(73, new Coordinate(0.04005, 0.01005)),
      Vertex(18, new Coordinate(-5.0E-5, 0.04005)),
      Vertex(17, new Coordinate(5.0E-5, 0.04005)),
      Vertex(38, new Coordinate(0.00995, 0.04005)),
      Vertex(48, new Coordinate(0.02005, 0.01995)),
      Vertex(2, new Coordinate(-5.0E-5, 5.0E-5)),
      Vertex(0, new Coordinate(5.0E-5, -5.0E-5)),
      Vertex(3, new Coordinate(-5.0E-5, -5.0E-5)),
      Vertex(1, new Coordinate(5.0E-5, 5.0E-5)),
      Vertex(30, new Coordinate(0.00995, 0.02005)),
      Vertex(72, new Coordinate(0.04005, 0.00995)),
      Vertex(62, new Coordinate(0.02995, 5.0E-5)),
      Vertex(63, new Coordinate(0.02995, -5.0E-5)),
      Vertex(59, new Coordinate(0.01995, 0.03995)),
      Vertex(51, new Coordinate(0.01995, 0.01995)),
      Vertex(46, new Coordinate(0.01995, 0.01005)),
      Vertex(60, new Coordinate(0.03005, -5.0E-5)),
      Vertex(61, new Coordinate(0.03005, 5.0E-5)),
      Vertex(49, new Coordinate(0.02005, 0.02005)),
      Vertex(19, new Coordinate(-5.0E-5, 0.03995)),
      Vertex(50, new Coordinate(0.01995, 0.02005)),
      Vertex(56, new Coordinate(0.02005, 0.03995)),
      Vertex(16, new Coordinate(5.0E-5, 0.03995)),
      Vertex(64, new Coordinate(0.03005, 0.03995)),
      Vertex(81, new Coordinate(0.04005, 0.03005)),
      Vertex(57, new Coordinate(0.02005, 0.04005)),
      Vertex(82, new Coordinate(0.03995, 0.03005)),
      Vertex(65, new Coordinate(0.03005, 0.04005)),
      Vertex(53, new Coordinate(0.02005, 0.03005)),
      Vertex(11, new Coordinate(-5.0E-5, 0.01995)),
      Vertex(12, new Coordinate(5.0E-5, 0.02995)),
      Vertex(8, new Coordinate(5.0E-5, 0.01995)),
      Vertex(84, new Coordinate(0.04005, 0.03995)),
      Vertex(15, new Coordinate(-5.0E-5, 0.02995)),
      Vertex(80, new Coordinate(0.04005, 0.02995)),
      Vertex(10, new Coordinate(-5.0E-5, 0.02005)),
      Vertex(9, new Coordinate(5.0E-5, 0.02005)),
      Vertex(21, new Coordinate(0.01005, 5.0E-5)),
      Vertex(20, new Coordinate(0.01005, -5.0E-5)),
      Vertex(67, new Coordinate(0.02995, 0.03995)),
      Vertex(87, new Coordinate(0.03995, 0.03995)),
      Vertex(78, new Coordinate(0.03995, 0.02005)),
      Vertex(45, new Coordinate(0.02005, 0.01005)),
      Vertex(7, new Coordinate(-5.0E-5, 0.00995)),
      Vertex(4, new Coordinate(5.0E-5, 0.00995)),
      Vertex(22, new Coordinate(0.00995, 5.0E-5)),
      Vertex(23, new Coordinate(0.00995, -5.0E-5)),
      Vertex(83, new Coordinate(0.03995, 0.02995)),
      Vertex(47, new Coordinate(0.01995, 0.00995)),
      Vertex(39, new Coordinate(0.00995, 0.03995)),
      Vertex(6, new Coordinate(-5.0E-5, 0.01005)),
      Vertex(5, new Coordinate(5.0E-5, 0.01005)),
      Vertex(55, new Coordinate(0.01995, 0.02995)),
      Vertex(71, new Coordinate(0.03995, -5.0E-5)),
      Vertex(36, new Coordinate(0.01005, 0.03995)),
      Vertex(70, new Coordinate(0.03995, 5.0E-5)),
    )
  )
}
