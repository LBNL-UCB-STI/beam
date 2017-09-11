package beam.agentsim.agents.choice.logit;

import java.util.LinkedHashMap;
import java.util.Random;

/**
 * BEAM
 */
public interface AbstractLogit {
    DiscreteProbabilityDistribution evaluateProbabilities(LinkedHashMap<String, LinkedHashMap<String, Double>> inputData);
    String makeRandomChoice(LinkedHashMap<String, LinkedHashMap<String, Double>> inputData, Random rand);
}
