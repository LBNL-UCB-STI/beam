package beam.physsim.cch

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.google.common.collect.Lists
import com.typesafe.config.ConfigFactory
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

class RoutingFrameworkTravelTimeCalculatorSpec extends FlatSpec with Matchers with MockitoSugar {
  private val beamConfig = BeamConfig(testConfig("test/input/beamville/beam.conf").resolve())
  private val wrapper = mock[RoutingFrameworkWrapper]
  private val infoHolder = mock[OsmInfoHolder]
  private val services = mock[BeamServices]
  when(services.beamConfig).thenReturn(beamConfig)
  when(wrapper.generateGraph()).thenReturn(RoutingFrameworkGraph(Nil))
  private val calculator = new RoutingFrameworkTravelTimeCalculator(
    services,
    infoHolder,
    wrapper
  )

  "RoutingFrameworkTravelTimeCalculator" must "create hour to travel infos map properly" in {
    val a = calculator.generateHour2Events(
      Lists.newArrayList(event(1, IndexedSeq(1), IndexedSeq(1)))
    )
    a

  }

  private def event(
    departureTime: Int,
    linkIds: IndexedSeq[Int],
    linkTravelTime: IndexedSeq[Double],
  ): PathTraversalEvent = {
    val e = mock[PathTraversalEvent]
    when(e.departureTime).thenReturn(departureTime)
    when(e.linkIds).thenReturn(linkIds)
    when(e.linkTravelTime).thenReturn(linkTravelTime)
    e
  }

}
