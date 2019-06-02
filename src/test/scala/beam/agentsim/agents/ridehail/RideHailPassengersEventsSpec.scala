package beam.agentsim.agents.ridehail

import beam.agentsim.events.PathTraversalEvent
import beam.integration.IntegrationSpecCommon
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

class RideHailPassengersEventsSpec extends WordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Vehicle" must {

    def initialSetup(eventHandler: BasicEventHandler): Unit = {
      val beamConfig = BeamConfig(baseConfig)
      val beamScenario = loadScenario(beamConfig)
      val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
      val matsimConfig = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      scenario.setNetwork(beamScenario.network)

      val injector = org.matsim.core.controler.Injector.createInjector(
        scenario.getConfig,
        module(baseConfig, scenario, beamScenario)
      )

      val beamServices: BeamServices =
        injector.getInstance(classOf[BeamServices])

      val eventManager: EventsManager =
        injector.getInstance(classOf[EventsManager])
      eventManager.addHandler(eventHandler)
      val popAdjustment = DefaultPopulationAdjustment
      popAdjustment(beamServices).update(scenario)
      beamServices.controler.run()
    }

    "keep passengers right count" in {
      val events = TrieMap[String, Tuple3[Int, Int, Int]]()

      initialSetup(new BasicEventHandler {

        override def handleEvent(event: Event): Unit = {
          event match {
            case traversalEvent: PathTraversalEvent if traversalEvent.vehicleId.toString.startsWith("rideHail") =>
              val id = traversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
              val numPass =
                traversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt
              val v = events.getOrElse(id, Tuple3(0, 0, 0))

              events.put(id, v.copy(_3 = v._3 + numPass))

              Set(numPass, 0) should contain(v._1 - v._2)

            case enterEvent: PersonEntersVehicleEvent
                if enterEvent.getVehicleId.toString
                  .startsWith("rideHail") && !enterEvent.getPersonId.toString.contains("Agent") =>
              val id = enterEvent.getVehicleId.toString
              val v = events.getOrElse(id, Tuple3(0, 0, 0))
              events.put(id, v.copy(_1 = v._1 + 1))

            case leavesEvent: PersonLeavesVehicleEvent if leavesEvent.getVehicleId.toString.startsWith("rideHail") =>
              val id = leavesEvent.getVehicleId.toString
              val v = events.getOrElse(id, Tuple3(0, 0, 0))
              events.put(id, v.copy(_2 = v._2 + 1))

            case _ =>
          }
        }
        Unit
      })

      events.forall(t => t._2._1 == t._2._2 && t._2._1 == t._2._3) shouldBe true
    }

    "keep single seat count" in {
      val events = TrieMap[String, Int]()

      initialSetup(new BasicEventHandler {

        override def handleEvent(event: Event): Unit = {
          event match {
            case enterEvent: PersonEntersVehicleEvent if !enterEvent.getPersonId.toString.contains("Agent") =>
              val id = enterEvent.getVehicleId.toString
//              events.get(id) shouldBe None
              events.put(id, 1)
            case leavesEvent: PersonLeavesVehicleEvent =>
              val id = leavesEvent.getVehicleId.toString
//              events.contains(id) shouldBe true
              events.remove(id)
            case _ =>
          }
        }

        Unit
      })
      events.isEmpty shouldBe true
    }

    "all passengers leave" in {
      val events = mutable.Set[String]()

      initialSetup {
        case enterEvent: PersonEntersVehicleEvent if !enterEvent.getPersonId.toString.contains("Agent") =>
          val vid = enterEvent.getVehicleId.toString
          val uid = enterEvent.getPersonId.toString
          events += s"$vid.$uid"
        case leavesEvent: PersonLeavesVehicleEvent =>
          val vid = leavesEvent.getVehicleId.toString
          val uid = leavesEvent.getPersonId.toString
          events -= s"$vid.$uid"
        case _ =>
      }
      events.isEmpty shouldBe true
    }
  }
}
