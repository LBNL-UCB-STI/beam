package beam.agentsim.agents.rideHail

import beam.agentsim.events.PathTraversalEvent
import beam.integration.IntegrationSpecCommon
import beam.router.r5.NetworkCoordinator
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.FileUtils
import org.matsim.api.core.v01.events.{
  Event,
  PersonEntersVehicleEvent,
  PersonLeavesVehicleEvent
}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

class RideHailPassengersEventsSpec
    extends WordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon {

  "Vehicle" must {

    def initialSetup(eventHandler: BasicEventHandler): Unit = {
      val beamConfig = BeamConfig(baseConfig)
      val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
      val matsimConfig = configBuilder.buildMatSamConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val networkCoordinator = new NetworkCoordinator(beamConfig)
      networkCoordinator.loadNetwork()

      val scenario =
        ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      scenario.setNetwork(networkCoordinator.network)

      val injector = org.matsim.core.controler.Injector.createInjector(
        scenario.getConfig,
        module(baseConfig, scenario, networkCoordinator.transportNetwork))

      val beamServices: BeamServices =
        injector.getInstance(classOf[BeamServices])
      val eventManager: EventsManager =
        injector.getInstance(classOf[EventsManager])
      eventManager.addHandler(eventHandler)

      beamServices.controler.run()
    }

    "keep passengers right count" in {
      val events = TrieMap[String, Int]()

      initialSetup(new BasicEventHandler {

        override def handleEvent(event: Event): Unit = {
          event match {
            case traversalEvent: PathTraversalEvent =>
              val id = traversalEvent.getAttributes.get(
                PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
              events.getOrElse(id, 0) shouldBe traversalEvent.getAttributes
                .get(PathTraversalEvent.ATTRIBUTE_NUM_PASS)
                .toInt
            case enterEvent: PersonEntersVehicleEvent =>
              val id = enterEvent.getVehicleId.toString
              events.update(id, events.getOrElse(id, 0) + 1)
            case leavesEvent: PersonLeavesVehicleEvent =>
              val id = leavesEvent.getVehicleId.toString
              events.update(id, events(id) - 1)
            case _ =>
          }
        }
        Unit
      })

      events.isEmpty shouldBe true
    }

    "keep single seat count" in {
      val events = TrieMap[String, Int]()

      initialSetup(new BasicEventHandler {

        override def handleEvent(event: Event): Unit = {
          event match {
            case enterEvent: PersonEntersVehicleEvent =>
              val id = enterEvent.getVehicleId.toString
              events.getOrElse(id, 0) should be <= 1
              events.update(id, events.getOrElse(id, 0) + 1)
            case leavesEvent: PersonLeavesVehicleEvent =>
              val id = leavesEvent.getVehicleId.toString
              events.update(id, events(id) - 1)
            case _ =>
          }
        }

        Unit
      })
      events.isEmpty shouldBe true
    }

    "all passangers leave" in {
      val events = mutable.Set[String]()

      initialSetup(new BasicEventHandler {

        override def handleEvent(event: Event): Unit = {
          event match {
            case enterEvent: PersonEntersVehicleEvent =>
              val vid = enterEvent.getVehicleId.toString
              val uid = enterEvent.getPersonId.toString
              events += s"$vid.$uid"
            case leavesEvent: PersonLeavesVehicleEvent =>
              val vid = leavesEvent.getVehicleId.toString
              val uid = leavesEvent.getPersonId.toString
              events -= s"$vid.$uid"
            case _ =>
          }
        }

        Unit
      })
      events.isEmpty shouldBe true
    }
  }
}
