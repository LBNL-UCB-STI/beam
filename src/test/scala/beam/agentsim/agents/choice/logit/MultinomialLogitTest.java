package beam.agentsim.agents.choice.logit;

import junit.framework.TestCase;

import java.util.LinkedHashMap;

public class MultinomialLogitTest extends TestCase {

	public void testMultinomialLogit(){

		double timeMultiplier = -1.0;
		double costMultiplier = -1.0;

		String xmlTestString = "<multinomialLogit name=\"mnl\">"
				+ "	<alternative name=\"alternative1\">"
				+ "		<utility>"
				+ "			<param name=\"intercept\" type=\"INTERCEPT\">1.0</param>"
				+ "			<param name=\"time\" type=\"MULTIPLIER\">"+timeMultiplier+"</param>"
				+ "			<param name=\"cost\" type=\"MULTIPLIER\">"+costMultiplier+"</param>"
				+ "		</utility>"
				+ "	</alternative>"
				+ "	<alternative name=\"alternative2\">"
				+ "		<utility>"
				+ "			<param name=\"intercept\" type=\"INTERCEPT\">1.0</param>"
				+ "			<param name=\"time\" type=\"MULTIPLIER\">"+timeMultiplier+"</param>"
				+ "			<param name=\"cost\" type=\"MULTIPLIER\">"+costMultiplier+"</param>"
				+ "		</utility>"
				+ "	</alternative>"
				+ "	<alternative name=\"alternative3\">"
				+ "		<utility>"
				+ "			<param name=\"intercept\" type=\"INTERCEPT\">3.0</param>"
				+ "			<param name=\"time\" type=\"MULTIPLIER\">"+timeMultiplier+"</param>"
				+ "			<param name=\"cost\" type=\"MULTIPLIER\">"+costMultiplier+"</param>"
				+ "		</utility>"
				+ "	</alternative>"
				+ "	<alternative name=\"alternative4\">"
				+ "		<utility>"
				+ "			<param name=\"intercept\" type=\"INTERCEPT\">1.0</param>"
				+ "			<param name=\"time\" type=\"MULTIPLIER\">"+timeMultiplier+"</param>"
				+ "			<param name=\"cost\" type=\"MULTIPLIER\">"+costMultiplier+"</param>"
				+ "		</utility>"
				+ "	</alternative>"
				+ "</multinomialLogit>";
		MulitnomialLogit model = MulitnomialLogit.MulitnomialLogitFactory(xmlTestString);

		
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

		DiscreteProbabilityDistribution cdf = model.evaluateProbabilities(inputData);
		int i = 0;
	}
}