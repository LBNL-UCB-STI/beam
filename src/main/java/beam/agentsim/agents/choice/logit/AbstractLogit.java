package beam.agentsim.agents.choice.logit;

import java.util.LinkedHashMap;
import java.util.Random;

/**
 * BEAM
 */
public interface AbstractLogit {
    public DiscreteProbabilityDistribution evaluateProbabilities(LinkedHashMap<String,LinkedHashMap<String,Double>> inputData);
    public String makeRandomChoice(LinkedHashMap<String,LinkedHashMap<String,Double>> inputData, Random rand);
}
