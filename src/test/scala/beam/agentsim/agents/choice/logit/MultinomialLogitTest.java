package beam.agentsim.agents.choice.logit;

import java.util.LinkedHashMap;

import junit.framework.TestCase;

public class MultinomialLogitTest extends TestCase {

	public void testMultinomialLogit(){

		String xmlTestString = "<multinomialLogit>"
				+ "	<elasticity>1</elasticity>"
				+ "	<utility>"
				+ "		<param name=\"intercept\" type=\"INTERCEPT\">1.0</param>"
				+ "		<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
				+ "		<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
				+ "	</utility>"
				+ "</multinomialLogit>";

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
		
		//String theChoice = model.makeRandomChoice(inputData);
	}
}