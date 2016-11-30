package beam.basicTests;

import static org.junit.Assert.*;

import java.util.LinkedList;

import org.junit.Test;
import org.matsim.api.core.v01.Id;

import beam.TestUtilities;
import beam.parking.lib.DebugLib;
import beam.parking.lib.obj.Matrix;
import beam.replanning.io.EVDailyPlanCsvFile;
import beam.replanning.io.EVDailyPlanUtils;

public class MultiIterationScoreImprovementSingleAgentTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {

		// in this test we check, if executed scores increases (mean) as we iterate through MATSim
		
		TestUtilities.setConfigFile("config_MultiIterationScoreImprovementSingleAgent.xml");

		startRun();
		
		int numberOfIterations=30;
		
		EVDailyPlanCsvFile[] evDailyPlanData=EVDailyPlanCsvFile.readIterations(numberOfIterations);
		
		double averageScoreSelectedSubSetIteration0To9 = EVDailyPlanUtils.getAverageScoreSelectedSubSet(evDailyPlanData,0,9,3);
		double averageScoreSelectedSubSetIteration10To19 = EVDailyPlanUtils.getAverageScoreSelectedSubSet(evDailyPlanData,10,19,3);
		double averageScoreSelectedSubSetIteration20To29 = EVDailyPlanUtils.getAverageScoreSelectedSubSet(evDailyPlanData,20,29,3);
		
		System.out.println(averageScoreSelectedSubSetIteration0To9);
		System.out.println(averageScoreSelectedSubSetIteration10To19);
		System.out.println(averageScoreSelectedSubSetIteration20To29);
		
		assertTrue("score is not improving", averageScoreSelectedSubSetIteration0To9<averageScoreSelectedSubSetIteration10To19 && averageScoreSelectedSubSetIteration10To19<averageScoreSelectedSubSetIteration20To29);
		
	}


	
}
