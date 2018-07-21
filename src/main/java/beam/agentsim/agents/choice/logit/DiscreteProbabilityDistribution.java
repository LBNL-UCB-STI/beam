package beam.agentsim.agents.choice.logit;

import java.util.*;


public class DiscreteProbabilityDistribution {

    private static final double PRECISION = 1e-6d;

    private LinkedHashMap<String, Double> pdf;
    private NavigableMap<Double, String> cdf;

    private static boolean isNumber(double value) {
        return !Double.isNaN(value);
    }

    void setPDF(Map<String, Double> pdf) {
        this.pdf = new LinkedHashMap<>(pdf);
        cdf = null;
    }

    private void createCDF() {
        if (pdf == null || pdf.isEmpty()) {
            throw new RuntimeException("Cannot create a CDF based on a missing or empty PDF");
        }

        final double scalingFactor = getScalingFactor();

        final String lastKey = updateCumulativeProbabilityAndReturnLastKey(scalingFactor);

        cdf = new TreeMap<>();
        cdf.remove(cdf.lastKey());
        cdf.put(1.0, lastKey);
    }

    private String updateCumulativeProbabilityAndReturnLastKey(final double scalingFactor) {
        String lastKey = null;

        double cumulativeProbability = 0.0d;
        for (Map.Entry<String, Double> entry : pdf.entrySet()) {
            final String key = entry.getKey();
            final Double value = entry.getValue();

            double theProb = Double.isInfinite(value) ? 1.0d : value / scalingFactor;
            if (theProb > PRECISION) {
                cumulativeProbability += theProb;
                cdf.put(cumulativeProbability, key);
                lastKey = key;
            }
        }

        return lastKey;
    }

    private double getScalingFactor() {
        double scalingFactor = 0.0;
        for (Double value : pdf.values()) {
            if (isNumber(value) && value > PRECISION) {
                scalingFactor += value;
            }
        }
        if (scalingFactor == 0.0d) {
            throw new RuntimeException("Cannot create a CDF based on a PDF without any non-zero probability density");
        }
        return scalingFactor;
    }

    Map<String, Double> getProbabilityDensityMap() {
        return Collections.unmodifiableMap(pdf);
    }

    public String sample(Random rand) {
        if (cdf == null) {
            createCDF();
        }
        return cdf.ceilingEntry(rand.nextDouble()).getValue();
    }

}
