package beam.basicTests;

import static org.junit.Assert.*;

import java.io.File;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.regex.Matcher;

import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.events.handler.EventHandler;

import beam.EVGlobalData;
import beam.EVSimTeleController;
import beam.TestUtilities;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.BeginChargingSessionEvent;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.EndChargingSessionEvent;
import beam.events.IdentifiableDecisionEvent;
import beam.events.ParkWithoutChargingEvent;
import beam.events.PreChargeEvent;
import beam.events.ReassessDecisionEvent;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.replanning.chargingStrategies.ChargingStrategiesTest;
import beam.sim.BayPTRouter;
import beam.sim.traveltime.BeamRouterImpl;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public class EnrouteChargingTest extends SingleAgentBaseTest {

	@Test
	public void test() {

		TestUtilities.setConfigFile("config_1_agent_enroute.xml");
		startRun();

		String assertionMessage;

		// assert that number of events generated are correct
		assertEquals(4, personArrivalEvents.size(), 0);
		assertEquals(4, personDepartureEvents.size(), 0);
		assertEquals(4, arrivalChargingDecisionEvents.size(), 0);
		assertEquals(5, departureChargingDecisionEvents.size(), 0);
		assertEquals(1, preChargeEvents.size(), 0);
		assertEquals(1, beginChargingSessionEvents.size(), 0);
		assertEquals(1, endChargingSessionEvents.size(), 0);
		assertEquals(4, parkWithoutChargingEvents.size(), 0);

		// confirm battery is depleted during driving and smaller than 100% when
		// arrival or reassess decisions are made
		assertTrue(reassessDecisionEvents.get(0).getSoC() < 1.0);
		for (int i = 0; i < 4; i++) {
			assertTrue(arrivalChargingDecisionEvents.get(i).getSoC() < 1.0);
		}

		Id<Person> agentOne = Id.createPersonId("1");

		ReassessDecisionEvent reassessDecisionEvent = reassessDecisionEvents.get(0);
		int decisionEventId = reassessDecisionEvent.getDecisionEventId();

		assertionMessage = "we are expecting one preCharging, one beginCharging and one endCharging event for each reassess decision in this scenario";
		assertOneDecisionEventElementPerDecisionEventID(assertionMessage, agentOne, decisionEventId, evEventCollector.eventCollection.get(0).preChargeEvents);
		assertOneDecisionEventElementPerDecisionEventID(assertionMessage, agentOne, decisionEventId, evEventCollector.eventCollection.get(0).beginChargingSessionEvents);
		assertOneDecisionEventElementPerDecisionEventID(assertionMessage, agentOne, decisionEventId, evEventCollector.eventCollection.get(0).endChargingSessionEvents);

		assertionMessage = "make sure that temporal order of reassess decision and subsequent charging events is correct";
		PreChargeEvent preChargeEvent = (PreChargeEvent) evEventCollector
				.getFilteredDecisionEventList(agentOne, decisionEventId, evEventCollector.eventCollection.get(0).preChargeEvents).getFirst();
		assertTrue(assertionMessage, preChargeEvent.getTime() >= reassessDecisionEvent.getTime());
		BeginChargingSessionEvent beginChargingSessionEvent = (BeginChargingSessionEvent) evEventCollector
				.getFilteredDecisionEventList(agentOne, decisionEventId, evEventCollector.eventCollection.get(0).beginChargingSessionEvents).getFirst();
		assertTrue(assertionMessage, beginChargingSessionEvent.getTime() >= preChargeEvent.getTime());
		EndChargingSessionEvent endChargingSessionEvent = (EndChargingSessionEvent) evEventCollector
				.getFilteredDecisionEventList(agentOne, decisionEventId, evEventCollector.eventCollection.get(0).endChargingSessionEvents).getFirst();
		assertTrue(assertionMessage, endChargingSessionEvent.getTime() >= beginChargingSessionEvent.getTime());

		assertionMessage = "as charging session duration was > 0, vehicle soc must increase!";
		if (endChargingSessionEvent.getTime() >= beginChargingSessionEvent.getTime()) {
			assertTrue(assertionMessage, endChargingSessionEvent.getSoC() > reassessDecisionEvent.getSoC());
		}
	}

}
