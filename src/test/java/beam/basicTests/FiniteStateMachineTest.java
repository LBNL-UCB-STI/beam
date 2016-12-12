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
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;

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
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.events.ActionEvent;
import beam.playground.metasim.metasim1.PlaygroundFun;
import beam.scoring.EVScoringEventCollector;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEvent;

public class FiniteStateMachineTest implements AfterMobsimListener{

	protected EVSimTeleController evSimTeleController = new EVSimTeleController();
	protected EVEventCollector evEventCollector = new EVEventCollector();
	protected LinkedList<ActionEvent> actionEvents;
	
	@Before
	public void setUp() {

	}

	@Test
	public void test() {
		
		TestUtilities.setConfigFile("config_1_agent.xml");
		startRun();

		String assertionMessage;

		// assert that number of events generated are correct
		assertEquals(5, actionEvents.size(), 0);

	}

	protected void startRun() {
		TestUtilities.initOutputFolder(this.getClass());
		TestUtilities.setTestInputDirectory(TestUtilities.getPathToyGridScenarioInputFolder());
		evSimTeleController.init();

		evSimTeleController.attachLinkTree();

		EVGlobalData.data.controler.getEvents().addHandler(evEventCollector);
		EVGlobalData.data.controler.addControlerListener(this);

		ChargingInfrastructureManagerImpl chargingInfrastructureManager = new ChargingInfrastructureManagerImpl();
		EVGlobalData.data.chargingInfrastructureManager = chargingInfrastructureManager;

		EVGlobalData.data.IS_TEST_CASE=true;
		
		evSimTeleController.startSimulation();

		Id<BeamAgent> agentOne = Id.create("1",BeamAgent.class);

		actionEvents = evEventCollector.eventCollection.get(0).actionEvents.get(agentOne);
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {
		PlaygroundFun.testBeamFSM();
	}

}
