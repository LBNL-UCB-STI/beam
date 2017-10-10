package beam.agentsim.agents.choice.logit;

import java.util.LinkedHashMap;
import java.util.Random;

/**
 * BEAM
 */
public interface AbstractLogit {
    public DiscreteProbabilityDistribution evaluateProbabilities(LinkedHashMap<String,LinkedHashMap<String,Double>> inputData);
    public String makeRandomChoice(LinkedHashMap<String,LinkedHashMap<String,Double>> inputData, Random rand);
    public Double getExpectedMaximumUtility();
    public void clear(); // Delete any state stored for use in evaluating distribution of given inputs
}
