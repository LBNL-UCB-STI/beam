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
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.replanning.chargingStrategies.ChargingStrategiesTest;
import beam.sim.BayPTRouter;
import beam.sim.traveltime.BeamRouterImpl;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public class MultiAgentBaseTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {

		TestUtilities.setConfigFile("config_7_agents.xml");

		startRun();

		LinkedList<Id<Person>> personIds = new LinkedList<>();

		for (int i = 1; i <= 7; i++) {
			personIds.add(Id.createPersonId(Integer.toString(i)));
		}

		// test case: check first charging decision of day
		// due to limited number of parking, only four people should be able to
		// charge at the preferred site (id: 9).
		// also in this test case test the radius expansion when preferred
		// parking full

		int numberOfAgentsWithFirstArrivalChargingAtSiteFive = 0, numberOfAgentsWithFirstArrivalChargingAtSiteNine=0;
		double initialSearchRadius = 0.5;
		for (Id<Person> personId : personIds) {
			if (personId.toString().equalsIgnoreCase("6")) {
				assertTrue("stranded agent not functioning properly",
						evEventCollector.eventCollection.get(0).arrivalChargingDecisionEvents.get(personId).size() == 0);
			} else {
				ArrivalChargingDecisionEvent firstActivityChargingDecisionOfDay = evEventCollector.eventCollection
						.get(0).arrivalChargingDecisionEvents.get(personId).getFirst();
				if (Integer.parseInt(firstActivityChargingDecisionOfDay.getChargingSiteId()) == 5) {
					numberOfAgentsWithFirstArrivalChargingAtSiteFive++;
				} else if (Integer.parseInt(firstActivityChargingDecisionOfDay.getChargingSiteId()) == 9) {
					numberOfAgentsWithFirstArrivalChargingAtSiteNine++;
				} else {
					// TODO: create test case for this again:
					// assertTrue("if site full, search radius needs to be made
					// wider",
					// firstActivityChargingDecisionOfDay.getSearchRadius() >
					// initialSearchRadius);
				}
			}
		}
		assertEquals(4, numberOfAgentsWithFirstArrivalChargingAtSiteNine, 0);
		assertEquals(2, numberOfAgentsWithFirstArrivalChargingAtSiteFive, 0);

		// test case: while 4 parking available, only two plugs available.
		// in the current scenario, all agents besides two decide to go charging
		// further away
		int numberOfAgentsWithLastArrivalChargingAtSiteOne = 0;
		for (Id<Person> personId : personIds) {
			if (personId.toString().equalsIgnoreCase("6")) {
				assertTrue("stranded agent not functioning properly",
						evEventCollector.eventCollection.get(0).arrivalChargingDecisionEvents.get(personId).size() == 0);
			} else {
				ArrivalChargingDecisionEvent lastActivityChargingDecisionOfDay = evEventCollector.eventCollection.get(0).arrivalChargingDecisionEvents
						.get(personId).getLast();
				if (Integer.parseInt(lastActivityChargingDecisionOfDay.getChargingSiteId()) == 1) {
					numberOfAgentsWithLastArrivalChargingAtSiteOne++;
				}
			}
		}
		assertEquals(2, numberOfAgentsWithLastArrivalChargingAtSiteOne, 0);

		// test case: all departure and arrival decision events must have unqiue
		// ids in the simulation!
		LinkedList<IdentifiableDecisionEvent> identifiableDecisionEvents = new LinkedList<>();
		HashSet<Integer> decisionEventIds = new HashSet<>();

		for (Id<Person> personId : personIds) {
			identifiableDecisionEvents.addAll(evEventCollector.eventCollection.get(0).arrivalChargingDecisionEvents.get(personId));
			identifiableDecisionEvents.addAll(evEventCollector.eventCollection.get(0).departureChargingDecisionEvents.get(personId));
		}

		for (IdentifiableDecisionEvent identifiableDecisionEvent : identifiableDecisionEvents) {
			decisionEventIds.add(identifiableDecisionEvent.getDecisionEventId());
		}

		assertEquals(identifiableDecisionEvents.size(), decisionEventIds.size(), 0);

		assertTrue(evEventCollector.eventCollection.get(0).departureChargingDecisionEvents.get(Id.createPersonId(6)).getFirst().getAttributes()
				.get("choice").equals("stranded"));
		assertTrue(evEventCollector.eventCollection.get(0).departureChargingDecisionEvents.get(Id.createPersonId(7)).getFirst().getAttributes()
				.get("choice").equals("depart"));
	}

}
