package beam.agentsim.agents.ridehail

import java.util.concurrent.atomic.AtomicInteger

import beam.agentsim.events.PathTraversalEvent
import beam.integration.IntegrationSpecCommon
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.vehicles.Vehicle
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.concurrent.TrieMap

class RideHailPassengersEventsSpec extends WordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Vehicle" must {
    "keep passengers right count" ignore {
      val events = TrieMap[String, Tuple3[Int, Int, Int]]()
      val nErrors = new AtomicInteger(0)

      initialSetup(new BasicEventHandler {

        override def handleEvent(event: Event): Unit = {
          event match {
            case traversalEvent: PathTraversalEvent if traversalEvent.vehicleId.toString.startsWith("rideHail") =>
              val id = traversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
              val numPass =
                traversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt
              val v = events.getOrElse(id, Tuple3(0, 0, 0))

              events.put(id, v.copy(_3 = v._3 + numPass))
              val diff = v._1 - v._2
              if (diff != 0 || diff != numPass) {
                nErrors.getAndIncrement()
              }

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

      if (events.nonEmpty) {
        logger.info(s"events: ${events.mkString(" ")}")
      }

      events.forall(t => t._2._1 == t._2._2 && t._2._1 == t._2._3) shouldBe true
      nErrors.get() shouldBe 0
    }

    "keep single seat count" in {
      val events = TrieMap[String, Int]()

      initialSetup(new BasicEventHandler {

        override def handleEvent(event: Event): Unit = {
          event match {
            case enterEvent: PersonEntersVehicleEvent
                if isRidehailRelated(enterEvent.getVehicleId, enterEvent.getPersonId) =>
              val id = enterEvent.getVehicleId.toString
              events.put(id, 1)
            case leavesEvent: PersonLeavesVehicleEvent
                if isRidehailRelated(leavesEvent.getVehicleId, leavesEvent.getPersonId) =>
              val id = leavesEvent.getVehicleId.toString
              events.remove(id)
            case _ =>
          }
        }
      })
      if (events.nonEmpty) {
        logger.info(s"events: ${events.mkString(" ")}")
      }
      events.isEmpty shouldBe true
    }

    "all passengers leave" in {
      val events = TrieMap[String, Int]()
      initialSetup(new BasicEventHandler {
        override def handleEvent(event: Event): Unit = {
          event match {
            case enterEvent: PersonEntersVehicleEvent
                if isRidehailRelated(enterEvent.getVehicleId, enterEvent.getPersonId) =>
              val vid = enterEvent.getVehicleId.toString
              val uid = enterEvent.getPersonId.toString
              events.put(s"$vid.$uid", 1)
            case leavesEvent: PersonLeavesVehicleEvent
                if isRidehailRelated(leavesEvent.getVehicleId, leavesEvent.getPersonId) =>
              val vid = leavesEvent.getVehicleId.toString
              val uid = leavesEvent.getPersonId.toString
              events.remove(s"$vid.$uid")
            case _ =>
          }
        }
      })
      if (events.nonEmpty) {
        logger.info(s"events: ${events.mkString(" ")}")
      }
      events.isEmpty shouldBe true
    }
  }

  def isRidehailRelated(vehicleId: Id[Vehicle], personId: Id[Person]): Boolean = {
    vehicleId.toString.startsWith("rideHail") && !personId.toString.contains("Agent")
  }

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
      module(baseConfig, beamConfig, scenario, beamScenario)
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
}
