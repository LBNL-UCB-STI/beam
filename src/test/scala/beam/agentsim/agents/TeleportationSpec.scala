package beam.agentsim.agents

import akka.actor.ActorSystem
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, TeleportationEvent}
import beam.router.Modes.BeamMode
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import org.matsim.api.core.v01.events.ActivityStartEvent
import org.matsim.core.controler
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.MutableScenario
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class TeleportationSpec extends AnyFunSpecLike with Matchers with BeamHelper with BeforeAndAfterAll {

  def runWithConfig(configPath: String, eventHandler: BasicEventHandler): Unit = {
    val config = testConfig(configPath).resolve()
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val (scenarioBuilt, beamScenario, _) = buildBeamServicesAndScenario(beamConfig, matsimConfig)
    val scenario: MutableScenario = scenarioBuilt

    val injector = controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(eventHandler)
        }
      }
    )
    implicit val actorSystem: ActorSystem = injector.getInstance(classOf[ActorSystem])
    val beamServices: BeamServices = buildBeamServices(injector)
    beamServices.controler.run()
  }

  describe("Run BEAM with teleportation") {

    describe("Run with multiple persons") {
      var teleportationEvents = 0
      val carHov3passengers = mutable.Set.empty[Int]
      val carHov2passengers = mutable.Set.empty[Int]
      val activitiesOfPerson2 = ListBuffer[(String, Double, String)]()
      val modeChoiceEvents = ListBuffer[(String, Double, String, String, String)]()
      runWithConfig(
        "test/input/beamville/beam-urbansimv2.conf",
        {
          case _: TeleportationEvent =>
            teleportationEvents = teleportationEvents + 1
          case e: PathTraversalEvent if e.currentTripMode.contains("car_hov3") && e.mode == BeamMode.CAR =>
            carHov3passengers.add(e.numberOfPassengers)
          case e: PathTraversalEvent if e.currentTripMode.contains("car_hov2") && e.mode == BeamMode.CAR =>
            carHov2passengers.add(e.numberOfPassengers)
          case e: ActivityStartEvent if e.getPersonId.toString == "2" =>
            activitiesOfPerson2.append((e.getLinkId.toString, e.getTime, e.getActType))
          case e: ModeChoiceEvent =>
            modeChoiceEvents.append(
              (e.getPersonId.toString, e.getTime, e.getMode, e.availableAlternatives, e.currentTourMode)
            )
          case _ =>
        }
      )
      it("should not have walk mode choices") {
        modeChoiceEvents.count(_._3.equalsIgnoreCase("walk")) shouldBe 0
      }

      it("should have teleportation events") {
        teleportationEvents shouldBe 12
      }

      it("should check the number of passengers in HOV cars") {
        carHov3passengers.toSet shouldBe Set(2)
        carHov2passengers.toSet shouldBe Set(1)
      }

      it("should check if activities happen at expected location with expected duration") {
        val activitiesList = activitiesOfPerson2.toList
        // links
        activitiesList.map(_._1) shouldBe List("300", "142", "300", "142", "300", "142")
        // times
        activitiesList.map(_._2).zip(List(21886.0, 26425.0, 32693.0, 37226.0, 39886.0, 44279.0)).foreach {
          case (a, b) => a shouldBe (b +- 600)
        }
        // type
        activitiesList.map(_._3) shouldBe List("Other", "Home", "Other", "Home", "Other", "Home")
      }

    }

    describe("Run with a single person") {
      it("if a person uses teleportation when there should be no PathTraversal events for a car") {
        var teleportationEvents = 0
        val pathTraversalModes = mutable.Set[BeamMode]()

        runWithConfig(
          "test/input/beamville/beam-urbansimv2_1person.conf",
          {
            case _: TeleportationEvent =>
              teleportationEvents = teleportationEvents + 1
            case e: PathTraversalEvent =>
              pathTraversalModes.add(e.mode)
            case _ =>
          }
        )
        teleportationEvents shouldBe 2
        pathTraversalModes.toSet shouldBe Set(BeamMode.BUS, BeamMode.SUBWAY)
      }
    }

  }

}
