package beam.physsim.cchRoutingAssignment

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.google.common.collect.Lists
import com.vividsolutions.jts.geom.Coordinate
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.utils.objectattributes.attributable.Attributes
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RoutingFrameworkTravelTimeCalculatorSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private val beamConfig = BeamConfig(testConfig("test/input/beamville/beam.conf").resolve())
  private val wrapper = mock(classOf[RoutingFrameworkWrapper])
  private val infoHolder = mock(classOf[OsmInfoHolder])
  private val services = mock(classOf[BeamServices])
  when(services.beamConfig).thenReturn(beamConfig)
  when(wrapper.generateGraph()).thenReturn(
    RoutingFrameworkGraph(Seq(Vertex(111, new Coordinate(0.05, 0.05)), Vertex(555, new Coordinate(0.3, 0.3))))
  )
  when(infoHolder.getCoordinatesForWayId(11)).thenReturn(Seq(new Coordinate(0.0, 0.0)))
  when(infoHolder.getCoordinatesForWayId(22)).thenReturn(Seq())
  when(infoHolder.getCoordinatesForWayId(44)).thenReturn(Seq(new Coordinate(0.4, 0.4)))
  when(infoHolder.getCoordinatesForWayId(55)).thenReturn(Seq(new Coordinate(0.3, 0.3)))

  private val calculator = new RoutingFrameworkTravelTimeCalculator(
    services,
    infoHolder,
    wrapper
  )

  "RoutingFrameworkTravelTimeCalculator" must "create hour to travel infos map properly" in {
    val infos = calculator.generateHour2Events(
      Lists.newArrayList(event(3500, IndexedSeq(1, 2, 3, 4, 5), IndexedSeq(30.0, 40.0, 40.0, 1300.0, 2500.0)))
    )
    val expectedInfos = Map(1 -> Seq(TravelInfo(IndexedSeq(4, 5))), 0 -> Seq(TravelInfo(IndexedSeq(1, 2, 3))))

    infos shouldBe expectedInfos
  }

  "RoutingFrameworkTravelTimeCalculator" must "create ods properly" in {
    calculator
      .generateOdsFromTravelInfos(
        Seq(
          TravelInfo(Vector(1, 2, 3, 4, 5)),
          TravelInfo(Vector(1, 2)),
          TravelInfo(Vector(5)),
          TravelInfo(Vector(2, 5))
        ),
        Map(
          1 -> link(11),
          2 -> link(22),
          3 -> link(33),
          4 -> link(44),
          5 -> link(55)
        )
      )
      .toList shouldBe List(OD(111, 555))
  }

  "RoutingFrameworkTravelTimeCalculator" must "fillLink2TravelTimeByHour properly" in {
    val (failedToResolve, res) = calculator
      .fillLink2TravelTimeByHour(
        Lists.newArrayList(link(11, 11), link(11, 12), link(22, 22), link(33, 33)),
        Map(
          1 -> Map(11L -> 10.0, 22L -> 20.0),
          2 -> Map(11L -> 15.0, 22L -> 5.0)
        ),
        3
      )
    failedToResolve shouldBe 1

    val expected = Map(
      "11" -> Array[Double](20.0, 5.0, 7.5),
      "12" -> Array[Double](20.0, 5.0, 7.5),
      "22" -> Array[Double](20.0, 20.0, 5.0),
      "33" -> Array[Double](20.0, 20.0, 20.0)
    )

    res.size shouldBe expected.size
    res.foreach { case (linkId, travelTimes) =>
      expected(linkId) shouldBe travelTimes
    }
  }

  private def event(
    departureTime: Int,
    linkIds: IndexedSeq[Int],
    linkTravelTime: IndexedSeq[Double]
  ): PathTraversalEvent = {
    val e = mock(classOf[PathTraversalEvent])
    when(e.departureTime).thenReturn(departureTime)
    when(e.linkIds).thenReturn(linkIds)
    when(e.linkTravelTime).thenReturn(linkTravelTime)
    e
  }

  private def link(
    wayId: Long,
    id: Long = 0
  ): Link = {
    val link = mock(classOf[Link])
    val attrs = new Attributes()
    attrs.putAttribute("origid", wayId.toString)
    when(link.getAttributes).thenReturn(attrs)
    when(link.getId).thenReturn(Id.create(id, classOf[Link]))
    when(link.getLength).thenReturn(1000.0)
    when(link.getFreespeed).thenReturn(50.0)
    link
  }

}
