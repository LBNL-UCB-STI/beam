package beam.utils.logging;

import org.apache.commons.math3.ml.clustering.Clusterable;

public class TextClusterable implements Clusterable {

    private final String text;

    TextClusterable(String text) {
        this.text = text;
    }

    @Override
    public double[] getPoint() {
        return TextClusterableUtil.toDoubles(text);
    }

    @Override
    public String toString() {
        return text;
    }
}
