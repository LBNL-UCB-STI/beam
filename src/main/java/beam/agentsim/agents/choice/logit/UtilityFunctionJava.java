package beam.agentsim.agents.choice.logit;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class UtilityFunctionJava {

    private final Map<String, Double> coefficients = new HashMap<>();
    private final Map<String, LogitCoefficientType> coefficientTypes = new HashMap<>();

    void addCoefficient(final String variableName, final double coefficient, final LogitCoefficientType coefficientType) {
        coefficients.put(variableName, coefficient);
        coefficientTypes.put(variableName, coefficientType);
    }

    double evaluateFunction(final Map<String, Double> valueMap) {
        double utility = 0.0d;

        for (Entry<String, Double> entry : coefficients.entrySet()) {
            final String coefficientKey = entry.getKey();
            final Double coefficientValue = entry.getValue();

            switch (coefficientTypes.get(coefficientKey)) {
                case INTERCEPT:
                    utility += coefficientValue;
                    break;
                case MULTIPLIER:
                    if (valueMap.get(coefficientKey) == null) {
                        throw new RuntimeException("Expecting variable " + coefficientKey + " but not contained in the input data");
                    }
                    utility += coefficientValue * valueMap.get(coefficientKey);
                    break;
                default:
                    break;
            }
        }
        return utility;
    }

    @Override
    public String toString() {
        return coefficients.entrySet()
                .stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

}

