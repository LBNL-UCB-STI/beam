package beam.utils.logging;

import org.apache.commons.math3.ml.distance.DistanceMeasure;
import org.apache.commons.text.similarity.LevenshteinDistance;

public class TextDistanceCalculator implements DistanceMeasure {

    @Override
    public double compute(double[] a, double[] b) {
        String text1 = TextClusterableUtil.toText(a);
        String text2 = TextClusterableUtil.toText(b);
        return LevenshteinDistance.getDefaultInstance().apply(text1, text2);
    }

}
