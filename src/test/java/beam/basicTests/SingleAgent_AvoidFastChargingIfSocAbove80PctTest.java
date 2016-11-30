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
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.events.EventsReaderXMLv1;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.events.handler.EventHandler;

import beam.EVGlobalData;
import beam.EVSimTeleController;
import beam.TestUtilities;
import beam.basicTests.SingleAgentBaseTest;
import beam.basicTests.EVEventCollector;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.BeginChargingSessionEvent;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.EndChargingSessionEvent;
import beam.events.ParkWithoutChargingEvent;
import beam.events.PreChargeEvent;
import beam.parking.lib.obj.IntegerValueHashMap;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.replanning.chargingStrategies.ChargingStrategiesTest;
import beam.sim.BayPTRouter;
import beam.sim.traveltime.BeamRouterImpl;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public class SingleAgent_AvoidFastChargingIfSocAbove80PctTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {
		TestUtilities.setConfigFile("config_1_agent_avoidFastChargingIfSocAbove80Pct.xml");
		
		startRun();
		
		// in the base single agent scenario site 8 and 4 are used
		// in the current scenario, we put fast chargers at site 8 and 4 -> this means those sites should 
		// be avoided by agent, as soc is above 80pct on arrival.
		String errorMessage="agent should not charge at fast charger";
		for (ArrivalChargingDecisionEvent arrivalChargingDecisionEvent:arrivalChargingDecisionEvents){
			assertFalse(errorMessage, arrivalChargingDecisionEvent.getChargingSiteId().equalsIgnoreCase("4"));
			assertFalse(errorMessage, arrivalChargingDecisionEvent.getChargingSiteId().equalsIgnoreCase("8"));
		}
	}

}
