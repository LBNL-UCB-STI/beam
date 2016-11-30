package beam.basicTests;

import static org.junit.Assert.*;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.regex.Matcher;

import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.population.Person;

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
import beam.scoring.EVScoringEventCollector;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEvent;

public class SingleAgentBaseTest {

	protected EVSimTeleController evSimTeleController = new EVSimTeleController();

	protected EVEventCollector evEventCollector = new EVEventCollector();

	protected LinkedList<PersonArrivalEvent> personArrivalEvents;
	protected LinkedList<PersonDepartureEvent> personDepartureEvents;
	protected LinkedList<ArrivalChargingDecisionEvent> arrivalChargingDecisionEvents;
	protected LinkedList<DepartureChargingDecisionEvent> departureChargingDecisionEvents;
	protected LinkedList<BeginChargingSessionEvent> beginChargingSessionEvents;
	protected LinkedList<EndChargingSessionEvent> endChargingSessionEvents;
	protected LinkedList<ParkWithoutChargingEvent> parkWithoutChargingEvents;
	protected LinkedList<PreChargeEvent> preChargeEvents;
	protected LinkedList<ReassessDecisionEvent> reassessDecisionEvents;
	private LinkedList<InternalRangeAnxityEvent> internalRangeAnxityEvents;

	protected EVScoringEventCollector evScoringEventCollector = new EVScoringEventCollector();

	
	
	@Before
	public void setUp() {

	}

	@Test
	public void test() {
		
		TestUtilities.setConfigFile("config_1_agent.xml");
		startRun();

		String assertionMessage;

		// assert that number of events generated are correct
		assertEquals(4, personArrivalEvents.size(), 0);
		assertEquals(4, personDepartureEvents.size(), 0);
		assertEquals(4, arrivalChargingDecisionEvents.size(), 0);
		assertEquals(4, departureChargingDecisionEvents.size(), 0);
		assertEquals(4, beginChargingSessionEvents.size(), 0);
		assertEquals(4, endChargingSessionEvents.size(), 0);
		assertEquals(0, parkWithoutChargingEvents.size(), 0);
		assertEquals(4, preChargeEvents.size(), 0);
		assertEquals(49, internalRangeAnxityEvents.size(), 0);
		

		// assert that leg mode set correctly
		for (int i = 0; i < 4; i++) {
			assertTrue(personArrivalEvents.get(i).getLegMode().equalsIgnoreCase(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES));
			assertTrue(personDepartureEvents.get(i).getLegMode().equalsIgnoreCase(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES));
		}

		// assert uniqueness of decisionEventId
		HashSet<Integer> tmp = new HashSet<>();
		for (ArrivalChargingDecisionEvent arrivalChargingDecisionEvent : arrivalChargingDecisionEvents) {
			tmp.add(arrivalChargingDecisionEvent.getDecisionEventId());
		}

		assertEquals(arrivalChargingDecisionEvents.size(), tmp.size(), 0);
		tmp.clear();

		for (DepartureChargingDecisionEvent departureChargingDecisionEvent : departureChargingDecisionEvents) {
			tmp.add(departureChargingDecisionEvent.getDecisionEventId());
		}

		assertEquals(departureChargingDecisionEvents.size(), tmp.size(), 0);
		tmp.clear();

		for (BeginChargingSessionEvent beginChargingSessionEvent : beginChargingSessionEvents) {
			tmp.add(beginChargingSessionEvent.getDecisionEventId());
		}

		assertEquals(beginChargingSessionEvents.size(), tmp.size(), 0);
		tmp.clear();

		for (EndChargingSessionEvent endChargingSessionEvent : endChargingSessionEvents) {
			tmp.add(endChargingSessionEvent.getDecisionEventId());
		}

		assertEquals(endChargingSessionEvents.size(), tmp.size(), 0);
		tmp.clear();

		for (PreChargeEvent preChargeEvent : preChargeEvents) {
			tmp.add(preChargeEvent.getDecisionEventId());
		}

		assertEquals(preChargeEvents.size(), tmp.size(), 0);
		tmp.clear();
		
		
		
		

		// confirm battery is depleted during driving and smaller than 100% when
		// charging starts at arrival
		for (int i = 0; i < 4; i++) {
			assertTrue(arrivalChargingDecisionEvents.get(i).getSoC() < 1.0);
		}

		// asserts related to arrival charging decision event
		Id<Person> agentOne = Id.createPersonId("1");

		for (ArrivalChargingDecisionEvent arrivalChargingDecisionEvent : arrivalChargingDecisionEvents) {
			int decisionEventId = arrivalChargingDecisionEvent.getDecisionEventId();

			assertionMessage = "we are expecting one preCharging, one beginCharging and one endCharging event for each arrival decision in this scenario";
			assertOneDecisionEventElementPerDecisionEventID(assertionMessage, agentOne, decisionEventId, evEventCollector.eventCollection.get(0).preChargeEvents);
			assertOneDecisionEventElementPerDecisionEventID(assertionMessage, agentOne, decisionEventId, evEventCollector.eventCollection.get(0).beginChargingSessionEvents);
			assertOneDecisionEventElementPerDecisionEventID(assertionMessage, agentOne, decisionEventId, evEventCollector.eventCollection.get(0).endChargingSessionEvents);

			assertionMessage = "make sure that temporal order of arrival decision events is correct";
			PreChargeEvent preChargeEvent = (PreChargeEvent) evEventCollector
					.getFilteredDecisionEventList(agentOne, decisionEventId, evEventCollector.eventCollection.get(0).preChargeEvents).getFirst();
			assertTrue(assertionMessage, preChargeEvent.getTime() >= arrivalChargingDecisionEvent.getTime());
			BeginChargingSessionEvent beginChargingSessionEvent = (BeginChargingSessionEvent) evEventCollector.getFilteredDecisionEventList(agentOne, decisionEventId, evEventCollector.eventCollection.get(0).beginChargingSessionEvents).getFirst();
			assertTrue(assertionMessage, beginChargingSessionEvent.getTime() >= preChargeEvent.getTime());
			EndChargingSessionEvent endChargingSessionEvent = (EndChargingSessionEvent) evEventCollector
					.getFilteredDecisionEventList(agentOne, decisionEventId, evEventCollector.eventCollection.get(0).endChargingSessionEvents).getFirst();
			assertTrue(assertionMessage, endChargingSessionEvent.getTime() >= beginChargingSessionEvent.getTime());

			assertionMessage = "as charging session duration was > 0, vehicle soc must increase!";
			if (endChargingSessionEvent.getTime() >= beginChargingSessionEvent.getTime()) {
				assertTrue(assertionMessage, endChargingSessionEvent.getSoC() > arrivalChargingDecisionEvent.getSoC());
			}
		}
		
	}

	protected void assertOneDecisionEventElementPerDecisionEventID(String assertionMessage, Id<Person> personId, int decisionEventId,
			LinkedListValueHashMap<Id<Person>, ? extends IdentifiableDecisionEvent> identifyableDecisionEvents) {

		LinkedList<IdentifiableDecisionEvent> identifiableEndChargingSessionEvents = evEventCollector.getFilteredDecisionEventList(personId,
				decisionEventId, identifyableDecisionEvents);
		assertTrue(assertionMessage, identifiableEndChargingSessionEvents.size() == 1);
	}

	protected void startRun() {
		TestUtilities.initOutputFolder(this.getClass());
		TestUtilities.setTestInputDirectory(TestUtilities.getPathToyGridScenarioInputFolder());
		evSimTeleController.init();

		evSimTeleController.attachLinkTree();

		EVGlobalData.data.controler.getEvents().addHandler(evEventCollector);
		EVGlobalData.data.controler.getEvents().addHandler(evScoringEventCollector);
		

		ChargingInfrastructureManagerImpl chargingInfrastructureManager = new ChargingInfrastructureManagerImpl();
		EVGlobalData.data.chargingInfrastructureManager = chargingInfrastructureManager;

		EVGlobalData.data.IS_TEST_CASE=true;
		
		evSimTeleController.startSimulation();

		// this just tests if router integration works - TODO: separate test
		// needed to verify if BeamRouterImpl works
		// assertEquals("Router integration not working correctly", expected,
		// actual);

		// [test if teleportation arrival/departure functioning properly] - TODO
		// (waiting): develop tests to end, when problem with Beam router/agent
		// causing wrong flow/null pointer exception solved
		// NOTE: it has been verified that at the event collector is functioning
		// fine.
		// TODO: we could remove the events again, if we don't need them here.

		Id<Person> agentOne = Id.createPersonId("1");

		personArrivalEvents = evEventCollector.eventCollection.get(0).personArrivalEvents.get(agentOne);
		personDepartureEvents = evEventCollector.eventCollection.get(0).personDepartureEvents.get(agentOne);
		arrivalChargingDecisionEvents = evEventCollector.eventCollection.get(0).arrivalChargingDecisionEvents.get(agentOne);
		departureChargingDecisionEvents = evEventCollector.eventCollection.get(0).departureChargingDecisionEvents.get(agentOne);
		beginChargingSessionEvents = evEventCollector.eventCollection.get(0).beginChargingSessionEvents.get(agentOne);
		endChargingSessionEvents = evEventCollector.eventCollection.get(0).endChargingSessionEvents.get(agentOne);
		parkWithoutChargingEvents = evEventCollector.eventCollection.get(0).parkWithoutChargingEvents.get(agentOne);
		preChargeEvents = evEventCollector.eventCollection.get(0).preChargeEvents.get(agentOne);
		reassessDecisionEvents = evEventCollector.eventCollection.get(0).reassessDecisionEvents.get(agentOne);
		internalRangeAnxityEvents = evEventCollector.eventCollection.get(0).internalRangeAnxityEvents.get(agentOne);
	}

}
