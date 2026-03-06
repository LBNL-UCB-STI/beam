package beam.integration

import akka.actor._
import akka.testkit.TestKitBase
import beam.agentsim.agents.PersonTestUtil
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.events.{LeavingParkingEvent, ParkingEvent}
import beam.agentsim.events.PathTraversalEvent
import beam.replanning.ModeIterationPlanCleaner
import beam.router.Modes.BeamMode
import beam.router.RouteHistory
import beam.sflight.RouterForTest
import beam.sim.common.GeoUtilsImpl
import beam.sim.population.PopulationScaling
import beam.sim.{BeamHelper, BeamMobsim, RideHailFleetInitializerProvider}
import beam.utils.{MathUtils, SimRunnerForTest}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events.{
  ActivityEndEvent,
  Event,
  PersonArrivalEvent,
  PersonDepartureEvent,
  PersonEntersVehicleEvent
}
import org.matsim.api.core.v01.population.{Activity, Leg}
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps

class SingleModeSpec
    extends AnyWordSpecLike
    with TestKitBase
    with SimRunnerForTest
    with RouterForTest
    with BeamHelper
    with Matchers {

  def config: com.typesafe.config.Config =
    ConfigFactory
      .parseString("""akka.test.timefactor = 10,
          |beam.agentsim.agentSampleSizeAsFractionOfPopulation = 0.25
          |beam.agentsim.randomSeedForPopulationSampling = 12345
          |beam.agentsim.agents.vehicles.generateEmergencyHouseholdVehicleWhenPlansRequireIt = true
          |beam.debug.stuckAgentDetection.enabled=true
          |""".stripMargin)
      .withFallback(testConfig("test/input/sf-light/sf-light-1k.conf").resolve())

  def outputDirPath: String = basePath + "/" + testOutputDir + "single-mode-test"

  lazy implicit val system: ActorSystem = ActorSystem("SingleModeSpec", config)

  override def beforeAll(): Unit = {
    super.beforeAll()
    Files.createDirectories(Paths.get(outputDirPath))
    PopulationScaling.samplePopulation(scenario, beamScenario, beamConfig, services, outputDirPath)
  }

  "The agentsim" must {
    "let everybody walk when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect { case leg: Leg =>
              leg.setMode("walk")
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      services.matsimServices.getEvents.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event: PersonDepartureEvent =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario),
        configHolder
      )
      mobsim.run()

      assert(events.nonEmpty)
      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      regularPersonEvents.map(_.getLegMode) should contain only "walk"
    }

    "let everybody take transit when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          person.getSelectedPlan.getPlanElements.asScala.collect { case leg: Leg =>
            leg.setMode("walk_transit")
          }
        }
      val events = mutable.ListBuffer[Event]()
      services.matsimServices.getEvents.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event: PersonDepartureEvent =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario),
        configHolder
      )
      mobsim.run()

      assert(events.nonEmpty)

      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      val (walkTransit, others) = regularPersonEvents.map(_.getLegMode).partition(_ == "walk_transit")
      others.size should be < (0.02 * walkTransit.size).toInt
    }

    "let everybody take drive_transit when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      // Here, we only set the mode for the first leg of each tour -- prescribing a mode for the tour,
      // but not for individual legs except the first one.
      // We want to make sure that our car is returned home.
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            val newPlanElements = person.getSelectedPlan.getPlanElements.asScala.collect {
              case activity: Activity if activity.getType == "Home" =>
                Seq(activity, scenario.getPopulation.getFactory.createLeg("drive_transit"))
              case activity: Activity =>
                Seq(activity)
                Seq(activity, scenario.getPopulation.getFactory.createLeg(""))
              case _: Leg => Nil
            }.flatten
            if (newPlanElements.last.isInstanceOf[Leg]) {
              newPlanElements.remove(newPlanElements.size - 1)
            }
            person.getSelectedPlan.getPlanElements.clear()
            newPlanElements.foreach {
              case activity: Activity =>
                person.getSelectedPlan.addActivity(activity)
              case leg: Leg =>
                person.getSelectedPlan.addLeg(leg)
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      services.matsimServices.getEvents.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event @ (_: PersonDepartureEvent | _: PersonArrivalEvent | _: ActivityEndEvent | _: ParkingEvent |
                  _: LeavingParkingEvent) =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario),
        configHolder
      )
      mobsim.run()

      assert(events.nonEmpty)
      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      val personArrivalEvents = events.collect { case event: PersonArrivalEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      val regularPersonArrivalEvents = personArrivalEvents.filterNot(event =>
        event.getLegMode == "be_a_tnc_driver" || event.getLegMode == "be_a_household_cav_driver" || event.getLegMode == "be_a_transit_driver" || event.getLegMode == "cav"
      )
      val driveTransitPersonIds =
        regularPersonEvents.filter(_.getLegMode == "drive_transit").map(_.getPersonId.toString).toSet
      val populationPersonIds = scenario.getPopulation.getPersons.keySet().asScala.map(_.toString).toSet
      val parkingEventsByPerson = events.collect {
        case event: ParkingEvent
            if populationPersonIds.contains(event.driverId) && driveTransitPersonIds.contains(event.driverId) =>
          event
      }.groupBy(_.driverId)
      val leavingParkingEventsByPerson = events.collect {
        case event: LeavingParkingEvent
            if populationPersonIds.contains(event.driverId) && driveTransitPersonIds.contains(event.driverId) =>
          event
      }.groupBy(_.driverId)
      val personsDriveTransitWalkOnly = parkingEventsByPerson.keySet.diff(leavingParkingEventsByPerson.keySet)
      val personsWalkTransitDriveOnly = leavingParkingEventsByPerson.keySet.diff(parkingEventsByPerson.keySet)
      val personsWithBothDriveTransitAndPickup =
        parkingEventsByPerson.keySet.intersect(leavingParkingEventsByPerson.keySet)
      val oneDirectionOnlyPersons = personsDriveTransitWalkOnly.size + personsWalkTransitDriveOnly.size
      val personsWithAnyPickupLifecycle = oneDirectionOnlyPersons + personsWithBothDriveTransitAndPickup.size
      val oneDirectionOnlyRate =
        if (personsWithAnyPickupLifecycle > 0)
          oneDirectionOnlyPersons.toDouble / personsWithAnyPickupLifecycle.toDouble
        else 0.0
      val maxAllowedOneDirectionOnlyRate = 0.50
      val eventsByMode = regularPersonEvents.groupBy(_.getLegMode)
      //router gives too little 'drive transit' trips, most of the persons chooses 'car' in this case
      val modeCount = eventsByMode.mapValues(_.size)
      val driveTransitDepartures = eventsByMode.get("drive_transit").map(_.size).getOrElse(0)
      val walkTransitDepartures = eventsByMode.get("walk_transit").map(_.size).getOrElse(0)
      val driveTransitArrivals = regularPersonArrivalEvents.count(_.getLegMode == "drive_transit")
      val driveTransitShareVsWalk =
        if (driveTransitDepartures + walkTransitDepartures > 0)
          driveTransitDepartures.toDouble / (driveTransitDepartures + walkTransitDepartures).toDouble
        else 0.0
      val driveTransitArrivalRate =
        if (driveTransitDepartures > 0) driveTransitArrivals.toDouble / driveTransitDepartures.toDouble else 0.0
      println(
        f"[SINGLEMODE-DRIVE-TRANSIT-METRICS] departuresTotal=${regularPersonEvents.size}%d " +
        f"driveDepartures=$driveTransitDepartures%d walkDepartures=$walkTransitDepartures%d " +
        f"driveArrivals=$driveTransitArrivals%d driveShareVsWalk=$driveTransitShareVsWalk%.6f " +
        f"driveArrivalRate=$driveTransitArrivalRate%.6f"
      )
      println(
        f"[SINGLEMODE-DRIVE-TRANSIT-PICKUP-METRICS] driveTransitWalkOnlyPersons=${personsDriveTransitWalkOnly.size}%d " +
        f"walkTransitDriveOnlyPersons=${personsWalkTransitDriveOnly.size}%d " +
        f"bothPickupAndDropPersons=${personsWithBothDriveTransitAndPickup.size}%d " +
        f"oneDirectionOnlyPersons=$oneDirectionOnlyPersons%d personsWithAnyPickupLifecycle=$personsWithAnyPickupLifecycle%d " +
        f"oneDirectionOnlyRate=$oneDirectionOnlyRate%.6f maxAllowedOneDirectionOnlyRate=$maxAllowedOneDirectionOnlyRate%.2f"
      )
      withClue(s"When transit is available drive_transit should remain viable: $modeCount") {
        driveTransitDepartures should be > 0
        driveTransitShareVsWalk should be > 0.25
        driveTransitArrivalRate should be > 0.85
        personsWithBothDriveTransitAndPickup.size should be > 0
        oneDirectionOnlyRate should be <= maxAllowedOneDirectionOnlyRate
      }

      // TODO: Test that what can be printed with the line below makes sense (chains of modes)
      //      filteredEventsByPerson.map(_._2.mkString("--\n","\n","--\n")).foreach(print(_))
    }

    "let everybody take bike_transit when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      // Here, we only set the mode for the first leg of each tour -- prescribing a mode for the tour,
      // but not for individual legs except the first one.
      // We want to make sure that our car is returned home.
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            val newPlanElements = person.getSelectedPlan.getPlanElements.asScala.collect {
              case activity: Activity if activity.getType == "Home" =>
                Seq(activity, scenario.getPopulation.getFactory.createLeg("bike_transit"))
              case activity: Activity =>
                Seq(activity)
                Seq(activity, scenario.getPopulation.getFactory.createLeg(""))
              case _: Leg => Nil
            }.flatten
            if (newPlanElements.last.isInstanceOf[Leg]) {
              newPlanElements.remove(newPlanElements.size - 1)
            }
            person.getSelectedPlan.getPlanElements.clear()
            newPlanElements.foreach {
              case activity: Activity =>
                person.getSelectedPlan.addActivity(activity)
              case leg: Leg =>
                person.getSelectedPlan.addLeg(leg)
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      services.matsimServices.getEvents.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event @ (_: PersonDepartureEvent | _: ActivityEndEvent) =>
                events += event
              case _ =>
            }
          }
        }
      )
      val mobsim = new BeamMobsim(
        services,
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario),
        configHolder
      )
      mobsim.run()

      assert(events.nonEmpty)
      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      val eventsByMode = regularPersonEvents.groupBy(_.getLegMode)
      val walkTransitDepartures = eventsByMode.get("walk_transit").map(_.size).getOrElse(0)
      val bikeTransitDepartures = eventsByMode.get("bike_transit").map(_.size).getOrElse(0)
      //router gives too little 'drive transit' trips, most of the persons chooses 'car' in this case
      withClue(
        s"When transit is available majority of agents should use bike_transit: walk_transit=$walkTransitDepartures bike_transit=$bikeTransitDepartures"
      ) {
        bikeTransitDepartures should be > 0
        walkTransitDepartures should be < bikeTransitDepartures
      }

      // TODO: Test that what can be printed with the line below makes sense (chains of modes)
      //      filteredEventsByPerson.map(_._2.mkString("--\n","\n","--\n")).foreach(print(_))
    }

    "let everybody drive when their plan says so" in {
      scenario.getPopulation.getPersons.values.asScala
        .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
      scenario.getPopulation.getPersons
        .values()
        .forEach { person =>
          {
            person.getSelectedPlan.getPlanElements.asScala.collect { case leg: Leg =>
              leg.setMode("car")
            }
          }
        }
      val events = mutable.ListBuffer[Event]()
      services.matsimServices.getEvents.addHandler(
        new BasicEventHandler {
          override def handleEvent(event: Event): Unit = {
            event match {
              case event @ (_: PersonDepartureEvent | _: ActivityEndEvent | _: PathTraversalEvent |
                  _: PersonEntersVehicleEvent) =>
                events += event
              case _ =>
            }
          }
        }
      )

      val mobsim = new BeamMobsim(
        services,
        beamScenario,
        beamScenario.transportNetwork,
        services.tollCalculator,
        scenario,
        services.matsimServices.getEvents,
        system,
        new RideHailSurgePricingManager(services),
        new RideHailIterationHistory(),
        new RouteHistory(services.beamConfig),
        new GeoUtilsImpl(services.beamConfig),
        new ModeIterationPlanCleaner(beamConfig, scenario),
        services.networkHelper,
        new RideHailFleetInitializerProvider(services, beamScenario, scenario),
        configHolder
      )
      mobsim.run()

      assert(events.nonEmpty)
      val personDepartureEvents = events.collect { case event: PersonDepartureEvent => event }
      personDepartureEvents should not be empty
      val regularPersonEvents = filterOutProfessionalDriversAndCavs(personDepartureEvents)
      val othersCount = regularPersonEvents.count(_.getLegMode != "car")
      withClue("Majority of agents should use cars. Other modes take place when no car available.") {
        othersCount should be < MathUtils.doubleToInt(0.02 * regularPersonEvents.size)
      }
    }
  }

  private def filterOutProfessionalDriversAndCavs(personDepartureEvents: ListBuffer[PersonDepartureEvent]) = {
    personDepartureEvents.filterNot(event =>
      event.getLegMode == "be_a_tnc_driver" || event.getLegMode == "be_a_household_cav_driver" || event.getLegMode == "be_a_transit_driver" || event.getLegMode == "cav"
    )
  }
}
