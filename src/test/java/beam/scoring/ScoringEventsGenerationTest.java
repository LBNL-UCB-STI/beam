package beam.scoring;

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
import beam.basicTests.SingleAgentBaseTest;
import beam.basicTests.EVEventCollector;
import beam.basicTests.EVEventsCollection;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.BeginChargingSessionEvent;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.EndChargingSessionEvent;
import beam.events.ParkWithoutChargingEvent;
import beam.events.PreChargeEvent;
import beam.parking.lib.DebugLib;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.replanning.chargingStrategies.ChargingStrategiesTest;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEvent;
import beam.sim.BayPTRouter;
import beam.sim.traveltime.BeamRouterImpl;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public class ScoringEventsGenerationTest extends SingleAgentBaseTest {

	
	
	@Override
	@Test
	public void test() {
		TestUtilities.setConfigFile("config_1_agent_scoring.xml");
		
		startRun();
		
		Id<Person> agentOne = Id.createPersonId("1");
		
		String message="number of scoring events not correct";
		EVEventsCollection evEventsCollectionIterationZero = evEventCollector.eventCollection.get(0);
		EVScoringEventsCollection evScoringEventsCollection = evScoringEventCollector.eventScoringEventsCollection.get(0);
		assertTrue(message, evEventsCollectionIterationZero.beginChargingSessionEvents.get(agentOne).size() == evScoringEventsCollection.chargingCostScoreEvents.get(agentOne).size());

		assertEquals(evEventsCollectionIterationZero.personArrivalEvents.get(agentOne).size(), evScoringEventsCollection.parkingScoreEvents.get(agentOne).size());

		assertEquals(evEventsCollectionIterationZero.personArrivalEvents.get(agentOne).size(), evScoringEventsCollection.legTravelTimeScoreEvents.get(agentOne).size());
		assertEquals(1,evScoringEventsCollection.rangeAnxietyScoreEvents.get(agentOne).size(),0);
		
		// we are logging internal events at 0.5 sec interval, as otherwise we would not see any change in soc
		message="something is wrong with internal range anxity events";
		assertTrue(message, evEventsCollectionIterationZero.internalRangeAnxityEvents.get(agentOne).size()>100000);
		
		int numberOfInternalRAEventsWithSocSmallerThanOne=0;
		for (InternalRangeAnxityEvent internalRAEvent: evEventsCollectionIterationZero.internalRangeAnxityEvents.get(agentOne)){
			if (internalRAEvent.getSoc()<1.0){
				numberOfInternalRAEventsWithSocSmallerThanOne++;
			}
		}
		
		assertTrue("number of internal range anxity events produced with soc<1 differs from what was expected", numberOfInternalRAEventsWithSocSmallerThanOne==14);
		
		
		// TODO: add parking score tests (walk and cost component).
		
		// TODO: test if score values are correct
		
		
	}

}
