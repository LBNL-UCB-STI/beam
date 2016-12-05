package beam.logit;

import java.util.LinkedHashMap;

import beam.EVSimTeleController;
import beam.TestUtilities;
import beam.logit.NestedLogit;
import beam.parking.lib.DebugLib;
import junit.framework.TestCase;

public class NestedLogitTest extends TestCase {
	protected EVSimTeleController evSimTeleController = new EVSimTeleController();
	
	public void testNestedLogit(){
		// Following is only necessary to get EVGlobal data initialized
		TestUtilities.setConfigFile("config_1_agent.xml");
		TestUtilities.setTestInputDirectory(TestUtilities.getPathToyGridScenarioInputFolder());
		evSimTeleController.init();

		String xmlTestString = "<nestedLogit name=\"top\">"
				+ "	<elasticity>1</elasticity>"
				+ "	<nestedLogit name=\"alternative1\">"
				+ "		<elasticity>1</elasticity>"
				+ "		<utility>"
				+ "			<param name=\"intercept\" type=\"INTERCEPT\">1.0</param>"
				+ "			<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
				+ "			<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
				+ "		</utility>"
				+ "	</nestedLogit>"
				+ "	<nestedLogit name=\"alternative2\">"
				+ "		<elasticity>1</elasticity>"
				+ "		<utility>"
				+ "			<param name=\"intercept\" type=\"INTERCEPT\">0.0</param>"
				+ "			<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
				+ "			<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
				+ "		</utility>"
				+ "	</nestedLogit>"
				+ "	<nestedLogit name=\"nest1\">"
				+ "		<elasticity>0.5</elasticity>"
				+ "		<nestedLogit name=\"alternative3\">"
				+ "			<elasticity>0.5</elasticity>"
				+ "			<utility>"
				+ "				<param name=\"intercept\" type=\"INTERCEPT\">-1.0</param>"
				+ "				<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
				+ "				<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
				+ "			</utility>"
				+ "		</nestedLogit>"
				+ "		<nestedLogit name=\"alternative4\">"
				+ "			<elasticity>0.5</elasticity>"
				+ "			<utility>"
				+ "				<param name=\"intercept\" type=\"INTERCEPT\">-2.0</param>"
				+ "				<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
				+ "				<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
				+ "			</utility>"
				+ "		</nestedLogit>"
				+ "	</nestedLogit>"
				+ "</nestedLogit>";
		
		NestedLogit model = NestedLogit.NestedLogitFactory(xmlTestString);
		
		LinkedHashMap<String, LinkedHashMap<String,Double>> inputData = new LinkedHashMap<>();
		LinkedHashMap<String,Double> altData = new LinkedHashMap<>();
		altData.put("time", 5.0);
		altData.put("cost", 5.0);
		inputData.put("alternative1", (LinkedHashMap<String,Double>)altData.clone());
		altData.put("time", 10.0);
		altData.put("cost", 0.0);
		inputData.put("alternative2", (LinkedHashMap<String,Double>)altData.clone());
		altData.put("time", 0.0);
		altData.put("cost", 15.0);
		inputData.put("alternative3", (LinkedHashMap<String,Double>)altData.clone());
		altData.put("time", 7.5);
		altData.put("cost", 2.5);
		inputData.put("alternative4", (LinkedHashMap<String,Double>)altData.clone());
		
		String theChoice = model.makeRandomChoice(inputData);
		DebugLib.emptyFunctionForSettingBreakPoint();
	}
}