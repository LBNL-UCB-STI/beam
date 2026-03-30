package beam.physsim.jdeqsim

import beam.sim.{BeamConfigChangesObservable, BeamHelper}
import beam.utils.EventReader.fromXmlFile
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Leg, Person, PlanElement, Population}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.population.routes.RouteUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.jdk.CollectionConverters._

class AgentEventsToPhyssimScenarioSpec extends AnyWordSpecLike with Matchers {
  val configPath = "test/input/beamville/beam-freight.conf"
  val beamTypesafeConfig: Config = testConfig(configPath).resolve()

  val beamHelper: BeamHelper = new BeamHelper {}
  val (execCfg, matsimScenario, beamScenario, beamSvc, _) = beamHelper.prepareBeamService(beamTypesafeConfig, None)

  val events: IndexedSeq[Event] = fromXmlFile(
    "test/test-resources/beam/agentsim/produced_events/freight-double-parking.xml.gz"
  )
  val eventsManager = new EventsManagerImpl

  val agentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(
    eventsManager,
    beamScenario.transportNetwork,
    beamSvc.matsimServices.getControlerIO,
    matsimScenario,
    beamSvc,
    new BeamConfigChangesObservable(execCfg.beamConfig, Some(configPath)),
    None
  )
  events.foreach(event => agentSimToPhysSimPlanConverter.handleEvent(event))
  val population: Population = agentSimToPhysSimPlanConverter.generatePopulation()

  "AgentSimToPhysSimPlanConverter" must {
    "generate correct physsim plans for Public Transport" in {
      val persons = population.getPersons.asScala
      persons.size shouldBe 1170
      val bus = persons(Id.createPersonId("bus:B1-EAST-1-0"))
      val busPlan = bus.getSelectedPlan.getPlanElements.asScala.toList
      busPlan.length shouldBe 9
      val initialAct :: leg1 :: stop1 :: leg2 :: stop2 :: leg3 :: stop3 :: leg4 :: finalDestination :: Nil = busPlan
      initialAct.asInstanceOf[Activity].getStartTime shouldBe 'undefined
      initialAct.asInstanceOf[Activity].getEndTime.seconds() shouldBe 21720.0
      leg1.asInstanceOf[Leg].getMode shouldBe "car"
      leg1.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 21720.0
      leg1.getAttributes.getAttribute("travel_time") shouldBe 90.0
      getLegLinks(leg1) should contain theSameElementsInOrderAs List(233, 252, 250, 244)
      stop1.asInstanceOf[Activity].getType shouldBe "DummyActivity"
      stop1.asInstanceOf[Activity].getEndTime.seconds() shouldBe 21930
      leg2.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 21930
      stop2.asInstanceOf[Activity].getEndTime.seconds() shouldBe 22140
      leg3.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 22140
      stop3.asInstanceOf[Activity].getEndTime.seconds() shouldBe 22350
      leg4.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 22350
      finalDestination.asInstanceOf[Activity].getEndTime shouldBe 'undefined
    }
    "generate correct physsim plans for private cars" in {
      val persons = population.getPersons.asScala
      val veh3 = persons(Id.createPersonId("3"))
      val veh3Plan = veh3.getSelectedPlan.getPlanElements.asScala.toList
      veh3Plan.length shouldBe 5
      val initialAct :: leg1 :: activity :: leg2 :: finalDestination :: Nil = veh3Plan
      initialAct.asInstanceOf[Activity].getStartTime shouldBe 'undefined
      initialAct.asInstanceOf[Activity].getEndTime.seconds() shouldBe 25246.0
      leg1.asInstanceOf[Leg].getMode shouldBe "car"
      leg1.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 25246.0
      leg1.getAttributes.getAttribute("travel_time") shouldBe 213.0
      leg1.getAttributes.getAttribute("ended_with_double_parking") shouldBe null
      getLegLinks(leg1) should contain theSameElementsInOrderAs List(228, 206, 180, 178, 184, 102, 108)
      activity.asInstanceOf[Activity].getType shouldBe "DummyActivity"
      activity.asInstanceOf[Activity].getEndTime.seconds() shouldBe 72007
      leg2.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 72007
      leg2.getAttributes.getAttribute("travel_time") shouldBe 286.0
      finalDestination.asInstanceOf[Activity].getEndTime shouldBe 'undefined
    }
    "generate correct physsim plans for freight with double-parking" in {
      val persons = population.getPersons.asScala
      val veh = persons(Id.createPersonId("freightVehicle-2"))
      val plan = veh.getSelectedPlan.getPlanElements.asScala.toList
      plan.length shouldBe 7
      val initialAct :: leg1 :: activity1 :: leg2 :: activity2 :: leg3 :: finalDestination :: Nil = plan
      initialAct.asInstanceOf[Activity].getStartTime shouldBe 'undefined
      initialAct.asInstanceOf[Activity].getEndTime.seconds() shouldBe 21000.0
      leg1.asInstanceOf[Leg].getMode shouldBe "car"
      leg1.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 21000.0
      leg1.getAttributes.getAttribute("travel_time") shouldBe 141.0
      val leg1TripId = Option(leg1.getAttributes.getAttribute("trip_id")).map(_.toString).getOrElse("")
      leg1TripId should not be empty
      getLegLinks(leg1) should contain theSameElementsInOrderAs List(309, 308)
      leg1.getAttributes.getAttribute("ended_with_double_parking") shouldBe true
      activity1.asInstanceOf[Activity].getType shouldBe "DummyActivity"
      activity1.asInstanceOf[Activity].getEndTime.seconds() shouldBe 23404
      leg2.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 23404
      leg2.getAttributes.getAttribute("travel_time") shouldBe 285.0
      val leg2TripId = Option(leg2.getAttributes.getAttribute("trip_id")).map(_.toString).getOrElse("")
      leg2TripId should not be empty
      leg2TripId should not be leg1TripId
      getLegLinks(leg2) should contain theSameElementsInOrderAs List(268, 348, 272, 278, 260, 266, 248, 246, 302)
      leg2.getAttributes.getAttribute("ended_with_double_parking") shouldBe true
      activity2.asInstanceOf[Activity].getEndTime.seconds() shouldBe 25509
      leg3.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 25509.0
      leg3.getAttributes.getAttribute("travel_time") shouldBe 498.0
      val leg3TripId = Option(leg3.getAttributes.getAttribute("trip_id")).map(_.toString).getOrElse("")
      leg3TripId should not be empty
      leg3TripId should not be leg2TripId
      getLegLinks(leg3) should contain theSameElementsInOrderAs List(146, 152, 70, 74, 96, 90, 112, 106, 100, 186, 176,
        368, 308, 268, 348, 295)
      leg3.getAttributes.getAttribute("ended_with_double_parking") shouldBe null
      finalDestination.asInstanceOf[Activity].getEndTime shouldBe 'undefined
    }
    "synchronize rerouted routes from physsim legs into agent sim selected plans" in {
      val networkLinkIds = matsimScenario.getNetwork.getLinks.keySet().asScala.toVector
      networkLinkIds.size should be >= 4

      val agentDriverId = Id.createPersonId("sync-test-driver")
      val physsimVehicleId = Id.createPersonId("sync-test-vehicle")

      val agentRouteIds = Seq(networkLinkIds(0), networkLinkIds(1))
      val reroutedIds = Seq(networkLinkIds(2), networkLinkIds(3))

      val agentDriver =
        createPersonWithSingleCarLeg(agentDriverId, agentRouteIds, departureTime = 1000.0, travelTime = 100.0)
      matsimScenario.getPopulation.addPerson(agentDriver)

      val reroutedVehicle = createPersonWithSingleCarLeg(
        physsimVehicleId,
        reroutedIds,
        departureTime = 2222.0,
        travelTime = 333.0
      )
      val reroutedLeg = reroutedVehicle.getSelectedPlan.getPlanElements.asScala.collectFirst { case leg: Leg =>
        leg
      }.get
      reroutedLeg.getAttributes.putAttribute("driver_id", agentDriverId.toString)
      reroutedLeg.getAttributes.putAttribute("rerouted_by_multi_jdeqsim", true)
      reroutedLeg.getAttributes.putAttribute("travel_time", 333.0)
      reroutedLeg.getAttributes.putAttribute("departure_time", 2222.0)
      reroutedLeg.getAttributes.putAttribute("event_time", 2555.0)
      population.addPerson(reroutedVehicle)

      try {
        val method = classOf[AgentSimToPhysSimPlanConverter]
          .getDeclaredMethod("synchronizePhysSimRoutesToAgentSimPlans", classOf[Int])
        method.setAccessible(true)
        method.invoke(agentSimToPhysSimPlanConverter, Integer.valueOf(0))

        val updatedAgentLeg = agentDriver.getSelectedPlan.getPlanElements.asScala.collectFirst { case leg: Leg =>
          leg
        }.get
        getLegLinksAsString(updatedAgentLeg) should contain theSameElementsInOrderAs getLegLinksAsString(reroutedLeg)
        updatedAgentLeg.getAttributes.getAttribute("travel_time") shouldBe 333.0
        updatedAgentLeg.getAttributes.getAttribute("departure_time") shouldBe 2222.0
        updatedAgentLeg.getAttributes.getAttribute("event_time") shouldBe 2555.0
        updatedAgentLeg.getAttributes.getAttribute("rerouted_by_multi_jdeqsim") shouldBe true
      } finally {
        matsimScenario.getPopulation.getPersons.remove(agentDriverId)
        population.getPersons.remove(physsimVehicleId)
      }
    }
    "synchronize rerouted routes when tripId and start link match but end link differs" in {
      val networkLinkIds = matsimScenario.getNetwork.getLinks.keySet().asScala.toVector
      networkLinkIds.size should be >= 7

      val agentDriverId = Id.createPersonId("sync-test-driver-tripid")
      val physsimVehicleId = Id.createPersonId("sync-test-vehicle-tripid")

      val firstAgentRoute = Seq(networkLinkIds(0), networkLinkIds(1), networkLinkIds(2))
      val secondAgentRoute = Seq(networkLinkIds(3), networkLinkIds(4), networkLinkIds(5))
      val reroutedIdsSameStartDifferentEnd = Seq(networkLinkIds(0), networkLinkIds(6))

      val agentDriver = createPersonWithTwoCarLegs(
        agentDriverId,
        firstAgentRoute,
        secondAgentRoute,
        firstDepartureTime = 2200.0,
        secondDepartureTime = 4000.0,
        firstTravelTime = 150.0,
        secondTravelTime = 250.0,
        firstTripId = "trip-a",
        secondTripId = "trip-b"
      )
      matsimScenario.getPopulation.addPerson(agentDriver)

      val reroutedVehicle = createPersonWithSingleCarLeg(
        physsimVehicleId,
        reroutedIdsSameStartDifferentEnd,
        departureTime = 2222.0,
        travelTime = 333.0,
        tripId = Some("trip-a")
      )
      val reroutedLeg = reroutedVehicle.getSelectedPlan.getPlanElements.asScala.collectFirst { case leg: Leg =>
        leg
      }.get
      reroutedLeg.getAttributes.putAttribute("driver_id", agentDriverId.toString)
      reroutedLeg.getAttributes.putAttribute("rerouted_by_multi_jdeqsim", true)
      reroutedLeg.getAttributes.putAttribute("travel_time", 333.0)
      reroutedLeg.getAttributes.putAttribute("departure_time", 2222.0)
      reroutedLeg.getAttributes.putAttribute("event_time", 2555.0)
      population.addPerson(reroutedVehicle)

      try {
        val method = classOf[AgentSimToPhysSimPlanConverter]
          .getDeclaredMethod("synchronizePhysSimRoutesToAgentSimPlans", classOf[Int])
        method.setAccessible(true)
        method.invoke(agentSimToPhysSimPlanConverter, Integer.valueOf(0))

        val updatedAgentCarLegs = agentDriver.getSelectedPlan.getPlanElements.asScala.collect { case leg: Leg =>
          leg
        }.toVector
        updatedAgentCarLegs.size shouldBe 2
        getLegLinksAsString(updatedAgentCarLegs.head) should contain theSameElementsInOrderAs getLegLinksAsString(
          reroutedLeg
        )
        updatedAgentCarLegs.head.getAttributes.getAttribute("travel_time") shouldBe 333.0
        updatedAgentCarLegs.head.getAttributes.getAttribute("departure_time") shouldBe 2222.0
        updatedAgentCarLegs.head.getAttributes.getAttribute("event_time") shouldBe 2555.0
        updatedAgentCarLegs.head.getAttributes.getAttribute("rerouted_by_multi_jdeqsim") shouldBe true
        getLegLinksAsString(updatedAgentCarLegs(1)) should contain theSameElementsInOrderAs secondAgentRoute.map(
          _.toString
        )
      } finally {
        matsimScenario.getPopulation.getPersons.remove(agentDriverId)
        population.getPersons.remove(physsimVehicleId)
      }
    }
  }

  private def createPersonWithSingleCarLeg(
    personId: Id[Person],
    routeLinkIds: Seq[Id[Link]],
    departureTime: Double,
    travelTime: Double,
    tripId: Option[String] = None
  ): Person = {
    val factory = matsimScenario.getPopulation.getFactory
    val person = factory.createPerson(personId)
    val plan = factory.createPlan()
    plan.setPerson(person)
    person.addPlan(plan)
    person.setSelectedPlan(plan)

    val origin = factory.createActivityFromLinkId("DummyActivity", routeLinkIds.head)
    origin.setEndTime(departureTime)
    plan.addActivity(origin)

    val leg = factory.createLeg("car")
    val route = RouteUtils.createNetworkRoute(routeLinkIds.asJava, matsimScenario.getNetwork)
    route.setTravelTime(travelTime)
    route.setDistance(1000.0)
    leg.setRoute(route)
    leg.setDepartureTime(departureTime)
    leg.setTravelTime(travelTime)
    leg.getAttributes.putAttribute("travel_time", travelTime)
    leg.getAttributes.putAttribute("departure_time", departureTime)
    tripId.foreach(value => leg.getAttributes.putAttribute("trip_id", value))
    plan.addLeg(leg)

    val destination = factory.createActivityFromLinkId("DummyActivity", routeLinkIds.last)
    plan.addActivity(destination)
    person
  }

  private def createPersonWithTwoCarLegs(
    personId: Id[Person],
    firstRouteLinkIds: Seq[Id[Link]],
    secondRouteLinkIds: Seq[Id[Link]],
    firstDepartureTime: Double,
    secondDepartureTime: Double,
    firstTravelTime: Double,
    secondTravelTime: Double,
    firstTripId: String,
    secondTripId: String
  ): Person = {
    val factory = matsimScenario.getPopulation.getFactory
    val person = factory.createPerson(personId)
    val plan = factory.createPlan()
    plan.setPerson(person)
    person.addPlan(plan)
    person.setSelectedPlan(plan)

    val origin = factory.createActivityFromLinkId("DummyActivity", firstRouteLinkIds.head)
    origin.setEndTime(firstDepartureTime)
    plan.addActivity(origin)

    val firstLeg = factory.createLeg("car")
    val firstRoute = RouteUtils.createNetworkRoute(firstRouteLinkIds.asJava, matsimScenario.getNetwork)
    firstRoute.setTravelTime(firstTravelTime)
    firstRoute.setDistance(1000.0)
    firstLeg.setRoute(firstRoute)
    firstLeg.setDepartureTime(firstDepartureTime)
    firstLeg.setTravelTime(firstTravelTime)
    firstLeg.getAttributes.putAttribute("travel_time", firstTravelTime)
    firstLeg.getAttributes.putAttribute("departure_time", firstDepartureTime)
    firstLeg.getAttributes.putAttribute("trip_id", firstTripId)
    plan.addLeg(firstLeg)

    val middle = factory.createActivityFromLinkId("DummyActivity", firstRouteLinkIds.last)
    middle.setEndTime(secondDepartureTime)
    plan.addActivity(middle)

    val secondLeg = factory.createLeg("car")
    val secondRoute = RouteUtils.createNetworkRoute(secondRouteLinkIds.asJava, matsimScenario.getNetwork)
    secondRoute.setTravelTime(secondTravelTime)
    secondRoute.setDistance(1000.0)
    secondLeg.setRoute(secondRoute)
    secondLeg.setDepartureTime(secondDepartureTime)
    secondLeg.setTravelTime(secondTravelTime)
    secondLeg.getAttributes.putAttribute("travel_time", secondTravelTime)
    secondLeg.getAttributes.putAttribute("departure_time", secondDepartureTime)
    secondLeg.getAttributes.putAttribute("trip_id", secondTripId)
    plan.addLeg(secondLeg)

    val destination = factory.createActivityFromLinkId("DummyActivity", secondRouteLinkIds.last)
    plan.addActivity(destination)
    person
  }

  private def getLegLinksAsString(leg: Leg): Seq[String] = {
    val route = leg.getRoute.asInstanceOf[NetworkRoute]
    val allLinks = route.getStartLinkId +: route.getLinkIds.asScala :+ route.getEndLinkId
    allLinks.map(_.toString)
  }

  private def getLegLinks(leg: PlanElement): Seq[Int] = {
    val route = leg.asInstanceOf[Leg].getRoute.asInstanceOf[NetworkRoute]
    val allLinks = route.getStartLinkId +: route.getLinkIds.asScala :+ route.getEndLinkId
    allLinks.map(_.toString.toInt)
  }
}
