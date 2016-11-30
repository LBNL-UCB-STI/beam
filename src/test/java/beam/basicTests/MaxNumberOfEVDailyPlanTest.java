package beam.basicTests;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.EVGlobalData;
import beam.TestUtilities;
import beam.parking.lib.obj.Matrix;
import beam.replanning.io.EVDailyPlanCsvFile;
import beam.replanning.io.EVDailyPlanWriter;

public class MaxNumberOfEVDailyPlanTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {
		TestUtilities.setConfigFile("config_MaxNumberOfEVDailyPlan.xml");
		startRun();
		
		int numberOfIterations=8;
		EVDailyPlanCsvFile[] evDailyPlanData=EVDailyPlanCsvFile.readIterations(numberOfIterations);
		
		Id<Person> personId = Id.createPersonId("1");
		
		
		for (int i=0;i<numberOfIterations;i++){
			assertTrue("more plans in memory than allowed", evDailyPlanData[i].getPlans(personId).size() <=2);
		}
		
	}
}
