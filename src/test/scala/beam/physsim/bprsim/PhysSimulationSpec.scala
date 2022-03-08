package beam.physsim.bprsim

import beam.physsim.bprsim.PhysSimulationSpec._
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{
  ActivityEndEvent,
  Event,
  HasLinkId,
  LinkEnterEvent,
  LinkLeaveEvent,
  PersonDepartureEvent,
  VehicleLeavesTrafficEvent
}
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.api.internal.HasPersonId
import org.matsim.core.config.{Config => MatsimConfig}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.mobsim.jdeqsim.{JDEQSimConfigGroup, JDEQSimulation}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * @author Dmitry Openkov
  */
class PhysSimulationSpec extends AnyWordSpecLike with Matchers {

  val config: Config = testConfig("test/input/beamville/beam.conf").resolve()

  val beamConfig: BeamConfig = BeamConfig(config)

  val configBuilder = new MatSimBeamConfigBuilder(config)
  val matsimConfig: MatsimConfig = configBuilder.buildMatSimConf()

  val network: Network = PhysSimulationSpec.readNetwork("test/test-resources/beam/physsim/beamville-network-output.xml")

  val scenario: MutableScenario =
    readScenario(matsimConfig, network, "test/test-resources/beam/physsim/physsim-plans-few-persons.xml")
  val jdeqConfig = new JDEQSimConfigGroup
  jdeqConfig.setFlowCapacityFactor(beamConfig.beam.physsim.flowCapacityFactor)
  jdeqConfig.setStorageCapacityFactor(beamConfig.beam.physsim.storageCapacityFactor)
  jdeqConfig.setSimulationEndTime(beamConfig.matsim.modules.qsim.endTime)

  "JDEQ Simulation" must {
    "produce the right sequence of events" in {
      val (eventManager: EventsManagerImpl, eventBuffer: BufferEventHandler) = createEventManager
      val sim = new JDEQSimulation(jdeqConfig, scenario, eventManager)
      sim.run()
      validateEvents(eventBuffer.buffer)
    }
  }

  "BPR Simulation" must {
    "produce the right sequence of events" in {
      val bprConfig =
        BPRSimConfig(
          jdeqConfig.getSimulationEndTime,
          1,
          0,
          1.0,
          900,
          (time, link, _, _) => link.getLength / link.getFreespeed(time),
          None
        )
      val (eventManager: EventsManagerImpl, eventBuffer: BufferEventHandler) = createEventManager
      val sim = new BPRSimulation(scenario, bprConfig, eventManager)
      sim.run()
      validateEvents(eventBuffer.buffer)
    }
  }

  "Parallel BPR Simulation" must {
    "produce the right sequence of events" in {
      val bprConfig =
        BPRSimConfig(
          jdeqConfig.getSimulationEndTime,
          8,
          60,
          1.0,
          900,
          (time, link, _, _) => link.getLength / link.getFreespeed(time),
          None
        )
      val (eventManager: EventsManagerImpl, eventBuffer: BufferEventHandler) = createEventManager
      val sim = new ParallelBPRSimulation(scenario, bprConfig, eventManager, 42)
      sim.run()
      validateEvents(eventBuffer.buffer)
    }
  }

  private def validateEvents(events: Seq[Event]) = {
    import scala.language.implicitConversions
    implicit def toLinkId(id: Int): Id[Link] = Id.createLinkId(id)

    events.size should be(640)
    val person10 = Id.createPersonId(10)
    val vehicle10 = Id.createVehicleId(10)
    val person10Events = events.collect {
      case event: HasPersonId if event.getPersonId == person10 => event
    }
    person10Events.size should be(22)
    person10Events(0) should be(an[ActivityEndEvent])
    person10Events(0).getTime should be(25247.0 +- 0.0001)
    person10Events(0).asInstanceOf[ActivityEndEvent].getLinkId should be(Id.createLinkId(228))
    person10Events(12) should be(an[PersonDepartureEvent])
    person10Events(12).getTime should be(72007.0 +- 0.0001)
    person10Events(12).asInstanceOf[PersonDepartureEvent].getLinkId should be(Id.createLinkId(34))
    person10Events(19) should be(an[VehicleLeavesTrafficEvent])
    person10Events(19).getTime should be > 72007.0
    person10Events(19).asInstanceOf[VehicleLeavesTrafficEvent].getLinkId should be(Id.createLinkId(229))

    val linkEvents = events.collect {
      case event: LinkEnterEvent if event.getVehicleId == vehicle10 => event.asInstanceOf[HasLinkId]
      case event: LinkLeaveEvent if event.getVehicleId == vehicle10 => event.asInstanceOf[HasLinkId]
    }
    linkEvents.size should be(32)
    linkEvents.zipWithIndex.foreach { case (event, i) =>
      if (i % 2 == 0) event should be(an[LinkLeaveEvent])
      else event should be(an[LinkEnterEvent])
    }
    linkEvents.map(_.getLinkId).toList should be(
      List[Id[Link]](
        228, 206, 206, 180, 180, 178, 178, 184, 184, 102, 34, 38, 38, 52, 52, 50, 50, 44, 44, 336, 336, 126, 126, 116,
        116, 202, 202, 192, 192, 316, 316, 196
      )
    )
  }
}

object PhysSimulationSpec {

  def readNetwork(path: String): Network = {
    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile(path)
    network.getLinks.values().asScala.foreach { link =>
      link.setCapacity(10000)
    }
    network
  }

  def readScenario(matsimConfig: MatsimConfig, network: Network, path: String): MutableScenario = {
    val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
    new PopulationReader(scenario).readFile(path)
    scenario.setNetwork(network)
    scenario
  }

  private def createEventManager = {
    val eventManager = new EventsManagerImpl
    val bufferEventHandler = new BufferEventHandler
    eventManager.addHandler(bufferEventHandler)
    (eventManager, bufferEventHandler)
  }
}

class BufferEventHandler extends BasicEventHandler {
  val buffer: ArrayBuffer[Event] = mutable.ArrayBuffer.empty[Event]

  override def handleEvent(event: Event): Unit = {
    buffer += event
  }
}
