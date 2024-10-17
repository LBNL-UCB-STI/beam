package beam.physsim.jdeqsim

import beam.sim.{BeamConfigChangesObservable, BeamHelper}
import beam.utils.EventReader.fromXmlFile
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.{Activity, Leg, PlanElement, Population}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.population.routes.NetworkRoute
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
      getLegLinks(leg1) should contain theSameElementsInOrderAs List(309, 308)
      leg1.getAttributes.getAttribute("ended_with_double_parking") shouldBe true
      activity1.asInstanceOf[Activity].getType shouldBe "DummyActivity"
      activity1.asInstanceOf[Activity].getEndTime.seconds() shouldBe 23404
      leg2.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 23404
      leg2.getAttributes.getAttribute("travel_time") shouldBe 285.0
      getLegLinks(leg2) should contain theSameElementsInOrderAs List(268, 348, 272, 278, 260, 266, 248, 246, 302)
      leg2.getAttributes.getAttribute("ended_with_double_parking") shouldBe true
      activity2.asInstanceOf[Activity].getEndTime.seconds() shouldBe 25509
      leg3.asInstanceOf[Leg].getDepartureTime.seconds() shouldBe 25509.0
      leg3.getAttributes.getAttribute("travel_time") shouldBe 498.0
      getLegLinks(leg3) should contain theSameElementsInOrderAs List(146, 152, 70, 74, 96, 90, 112, 106, 100, 186, 176,
        368, 308, 268, 348, 295)
      leg3.getAttributes.getAttribute("ended_with_double_parking") shouldBe null
      finalDestination.asInstanceOf[Activity].getEndTime shouldBe 'undefined
    }
  }

  private def getLegLinks(leg: PlanElement): Seq[Int] = {
    val route = leg.asInstanceOf[Leg].getRoute.asInstanceOf[NetworkRoute]
    val allLinks = route.getStartLinkId +: route.getLinkIds.asScala :+ route.getEndLinkId
    allLinks.map(_.toString.toInt)
  }
}
