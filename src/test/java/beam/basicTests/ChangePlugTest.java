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
import beam.scoring.EVScoringEventsCollection;
import beam.sim.BayPTRouter;
import beam.sim.traveltime.BeamRouterImpl;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public class ChangePlugTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {
		Id<Person> agentOne = Id.createPersonId("1");
		
		TestUtilities.setConfigFile("config-5-agents-constrained.xml");
		
		startRun();
		
		EVScoringEventsCollection evScoringEventsCollection = evScoringEventCollector.eventScoringEventsCollection.get(0);

		String message="plug change scoring event does not work properly";
		assertTrue(message,evScoringEventsCollection.changePlugOverheadEvents.get(agentOne).size()==2);
		assertEquals(30602.0,evScoringEventsCollection.changePlugOverheadEvents.get(agentOne).get(0).getTime(),0);
		assertEquals(47223.0,evScoringEventsCollection.changePlugOverheadEvents.get(agentOne).get(1).getTime(),0);
	}

}
