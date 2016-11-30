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
import beam.replanning.io.EVDailyPlanUtils;
import beam.replanning.io.EVDailyPlanWriter;
import beam.sim.BayPTRouter;
import beam.sim.traveltime.BeamRouterImpl;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public class NestedLogitScoreReproducabilitySingleAgentTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {

		// this test confirms that score of single agent do not change, if we do
		// not change the plan

		TestUtilities.setConfigFile("config_NestedLogitScoreReproducabilitySingleAgent.xml");

		startRun();

		int numberOfIterations = 2;
		EVDailyPlanCsvFile[] evDailyPlanData = EVDailyPlanCsvFile.readIterations(numberOfIterations);
		Id<Person> personId = Id.createPersonId("1");

		for (int i = 0; i < numberOfIterations; i++) {
			System.out.println(evDailyPlanData[i].getSelectedPlan(personId).chargingSequenceScore);
			System.out.println(evDailyPlanData[i].getSelectedPlan(personId).getChargingStrategyIds());
		}

		assertTrue("strategies are changing between iteration 0 and 1",
				EVDailyPlanUtils.strategySequenceMatches(evDailyPlanData[0].getSelectedPlan(personId).getChargingStrategyIds(),
						evDailyPlanData[1].getSelectedPlan(personId).getChargingStrategyIds()));
		assertTrue("the scores of iteration 0 and 1 do not match", evDailyPlanData[0]
				.getSelectedPlan(personId).chargingSequenceScore == evDailyPlanData[1].getSelectedPlan(personId).chargingSequenceScore);

	}
}
