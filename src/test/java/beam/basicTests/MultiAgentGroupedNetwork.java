package beam.basicTests;

import beam.TestUtilities;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.IdentifiableDecisionEvent;
import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.HashSet;
import java.util.LinkedList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MultiAgentGroupedNetwork extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {

		TestUtilities.setConfigFile("config_5_agents_groupedToyNetwork.xml");

		startRun();

	}

}
