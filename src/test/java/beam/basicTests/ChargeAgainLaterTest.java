package beam.basicTests;

import static org.junit.Assert.*;

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.regex.Matcher;

import org.jdom.Element;
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
import beam.TestingHooks;
import beam.basicTests.SingleAgentBaseTest;
import beam.basicTests.EVEventCollector;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.BeginChargingSessionEvent;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.EndChargingSessionEvent;
import beam.events.ParkWithoutChargingEvent;
import beam.events.PreChargeEvent;
import beam.parking.lib.obj.IntegerValueHashMap;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.replanning.ChargingStrategy;
import beam.replanning.StrategySequence;
import beam.replanning.chargingStrategies.ChargingStrategiesTest;
import beam.replanning.chargingStrategies.ChargingStrategyAlwaysChargeOnArrival;
import beam.sim.BayPTRouter;
import beam.sim.SearchAdaptationAlternative;
import beam.sim.traveltime.BeamRouterImpl;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public class ChargeAgainLaterTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {
		TestUtilities.setConfigFile("config_1_agent.xml");

		EVGlobalData.data.testingHooks.testCase_chargingStrategySequence = new HashMap<>();

		// for single agent base test case: force agent to do always try to
		// charge again later and make sure no error is produced during
		// execution of this

		HashMap<Integer, ChargingStrategy> strategies = new HashMap<>();

		strategies.put(1, new ChargingStrategyTryAgainLater(false));
		strategies.put(3, new ChargingStrategyTryAgainLater(false));
		strategies.put(5, new ChargingStrategyTryAgainLater(false));
		strategies.put(7, new ChargingStrategyTryAgainLater(true));

		EVGlobalData.data.testingHooks.testCase_chargingStrategySequence.put(Id.createPersonId("1"), new StrategySequence(strategies));

		startRun();

		EVGlobalData.data.testingHooks.assertNoRunTimeError();

		// TODO: expand test later - for the moment, if this test just runs
		// without error, we are ok.

	}

	
	private class ChargingStrategyTryAgainLater extends ChargingStrategyAlwaysChargeOnArrival {

		private boolean isLastLeg;
		boolean turnedOnAlwaysChargeOnArrival = false;

		public ChargingStrategyTryAgainLater(boolean isLastLeg) {
			super();
			this.isLastLeg = isLastLeg;
		}


		@Override
		public SearchAdaptationAlternative getChosenAdaptationAlternativeOnArrival(PlugInVehicleAgent agent) {
			if (!isLastLeg) {
				return SearchAdaptationAlternative.TRY_CHARGING_LATER;
			} else {
				if (turnedOnAlwaysChargeOnArrival) {
					return super.getChosenAdaptationAlternativeOnArrival(agent);
				} else {
					turnedOnAlwaysChargeOnArrival = true;
					return SearchAdaptationAlternative.TRY_CHARGING_LATER;
				}
			}
		}
		
		@Override
		public boolean hasChosenToChargeOnArrival(PlugInVehicleAgent agent) {
			super.hasChosenToChargeOnArrival(agent);
			if(!turnedOnAlwaysChargeOnArrival){
				super.arrivalChargingChoices.get(super.arrivalChargingChoices.size()-1).chosenChargingPlug = null;
				super.arrivalChargingChoices.get(super.arrivalChargingChoices.size()-1).chosenAdaptation = SearchAdaptationAlternative.ABORT;
			}
			return turnedOnAlwaysChargeOnArrival;
		}
//		
//		@Override
//		public ChargingPlug chooseChargingAlternativeOnArrival(PlugInVehicleAgent agent) {
//			return chooseChargingAlternativeOnDeparture(agent);
//		}
//		
//		@Override
//		public ChargingPlug chooseChargingAlternativeOnDeparture(PlugInVehicleAgent agent) {
//			if (turnedOnAlwaysChargeOnArrival){
//				return this.chosenPlug;
//			} else {
//				return null;
//			}
//		}
	}

}
