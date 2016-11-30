package beam.events;

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

public class EventLogger_SettingDefaultLoggingLevel extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {
		TestUtilities.setConfigFile("config_SettingDefaultLoggingLevel.xml");
		
		startRun();
		
		final IntegerValueHashMap<String> eventCounter = getEventCounter();
		
		// assert that number of events generated are correct
		assertEquals(0, eventCounter.get(ArrivalChargingDecisionEvent.class.getSimpleName()) , 0);
		assertEquals(0, eventCounter.get(DepartureChargingDecisionEvent.class.getSimpleName()) , 0);
		assertEquals(4, eventCounter.get(BeginChargingSessionEvent.class.getSimpleName()) , 0);
		assertEquals(4, eventCounter.get(EndChargingSessionEvent.class.getSimpleName()) , 0);
		assertEquals(0, eventCounter.get(ParkWithoutChargingEvent.class.getSimpleName()) , 0);
		assertEquals(4, eventCounter.get(PreChargeEvent.class.getSimpleName()) , 0);
	}

	protected IntegerValueHashMap<String> getEventCounter() {
		EventsManager events = EventsUtils.createEventsManager();
		
		final IntegerValueHashMap<String> eventCounter=new IntegerValueHashMap<>();
		
		//TODO: build proper event converter for custom events - see also
		// org.matsim.core.events.CustomEventTest
		events.addHandler(new BasicEventHandler() {
			@Override
			public void handleEvent(Event event) {
				eventCounter.increment(event.getEventType());
			}

			@Override
			public void reset(int iteration) {

			}
		});
		
		MatsimEventsReader reader = new MatsimEventsReader(events);
		reader.readFile(EVGlobalData.data.controler.getControlerIO().getIterationFilename(0, "events.xml.gz"));
		return eventCounter;
	}

}
