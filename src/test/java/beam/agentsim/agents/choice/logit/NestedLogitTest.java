package beam.agentsim.agents.choice.logit;

import junit.framework.TestCase;

import java.util.LinkedHashMap;

public class NestedLogitTest extends TestCase {

    public void testNestedLogit() {

        String xmlTestString = "<nestedLogit name=\"top\">"
                + "	<elasticity>1</elasticity>"
                + "	<alternative name=\"alternative1\">"
                + "		<elasticity>1</elasticity>"
                + "		<utility>"
                + "			<param name=\"intercept\" type=\"INTERCEPT\">1.0</param>"
                + "			<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
                + "			<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
                + "		</utility>"
                + "	</alternative>"
                + "	<alternative name=\"alternative2\">"
                + "		<elasticity>1</elasticity>"
                + "		<utility>"
                + "			<param name=\"intercept\" type=\"INTERCEPT\">0.0</param>"
                + "			<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
                + "			<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
                + "		</utility>"
                + "	</alternative>"
                + "	<nestedLogit name=\"nest1\">"
                + "		<elasticity>0.5</elasticity>"
                + "		<alternative name=\"alternative3\">"
                + "			<elasticity>0.5</elasticity>"
                + "			<utility>"
                + "				<param name=\"intercept\" type=\"INTERCEPT\">-1.0</param>"
                + "				<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
                + "				<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
                + "			</utility>"
                + "		</alternative>"
                + "		<alternative name=\"alternative4\">"
                + "			<elasticity>0.5</elasticity>"
                + "			<utility>"
                + "				<param name=\"intercept\" type=\"INTERCEPT\">-2.0</param>"
                + "				<param name=\"time\" type=\"MULTIPLIER\">1.0</param>"
                + "				<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
                + "			</utility>"
                + "		</alternative>"
                + "	</nestedLogit>"
                + "</nestedLogit>";

        LinkedHashMap<String, LinkedHashMap<String, Double>> inputData = new LinkedHashMap<>();
        LinkedHashMap<String, Double> altData = new LinkedHashMap<>();
        altData.put("time", 5.0);
        altData.put("cost", 5.0);
        inputData.put("alternative1", altData);
        altData = new LinkedHashMap<>();
        altData.put("time", 10.0);
        altData.put("cost", 0.0);
        inputData.put("alternative2", altData);
        altData = new LinkedHashMap<>();
        altData.put("time", 0.0);
        altData.put("cost", 15.0);
        inputData.put("alternative3", altData);
        altData = new LinkedHashMap<>();
        altData.put("time", 7.5);
        altData.put("cost", 2.5);
        inputData.put("alternative4", altData);

        //String theChoice = model.makeRandomChoice(inputData, new Random());
    }
}