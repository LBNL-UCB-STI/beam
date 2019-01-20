package beam.utils;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class TravelTimeCalculatorHelperTest {
    double error = 1E-6;

    @Test
    public void AverageTravelTimesMapShouldWorkProperly() {
        int hours = 10;
        // The same numbers in both arrays, so it will be easy to test average
        double[] times1 = new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
        double[] times2 = new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
        Map<String, double[]> map1 = new HashMap<>();
        map1.put("1", times1);

        Map<String, double[]> map2 = new HashMap<>();
        map2.put("1", times2);

        Map<String, double[]> result = TravelTimeCalculatorHelper.AverageTravelTimesMap(map1, map2, hours);
        double[] resArr = result.get("1");
        for(int i = 0; i < hours; i++) {
            double diff = Math.abs(resArr[i] - times1[i]);
            assert diff <= error;
        }
    }
}
