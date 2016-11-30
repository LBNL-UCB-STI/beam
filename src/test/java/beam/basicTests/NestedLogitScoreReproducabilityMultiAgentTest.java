package beam.basicTests;

import static org.junit.Assert.*;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
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
import beam.parking.lib.GeneralLib;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.parking.lib.obj.Matrix;
import beam.replanning.chargingStrategies.ChargingStrategiesTest;
import beam.replanning.io.EVDailyPlanCsvFile;
import beam.replanning.io.EVDailyPlanWriter;
import beam.sim.BayPTRouter;
import beam.sim.traveltime.BeamRouterImpl;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public class NestedLogitScoreReproducabilityMultiAgentTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {

		// testing, if charging sequence changes using
		// RandomChargingStrategySelector -> Multi iterations test

		TestUtilities.setConfigFile("config_NestedLogitScoreReproducabilityMultiAgent.xml");

		startRun();

		int numberOfIterations = 2;
		EVDailyPlanCsvFile[] evDailyPlanData=EVDailyPlanCsvFile.readIterations(numberOfIterations);
		
		for (Id<Person> personId:evDailyPlanData[0].getPersonIds()){
			assertTrue("plan scores are changing while same strategy and plan used by all agents!!", evDailyPlanData[0].getSelectedPlan(personId).planScore==evDailyPlanData[1].getSelectedPlan(personId).planScore);
			assertTrue("charging scores are changing while same strategy and plan used by all agents!!", evDailyPlanData[0].getSelectedPlan(personId).chargingSequenceScore==evDailyPlanData[1].getSelectedPlan(personId).chargingSequenceScore);
		}
		
		
	}
}
