package beam.utils.logging;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class TextDistanceCalculatorUtilTest {

    private static final double DELTA = 0D;

    @Test
    public void textCanBeConvertedToByteArrayAndViceVersa() {
        String text = "bla blé blõwn∫ çoisXXX";
        byte[] bytes = TextClusterableUtil.toBytes(text);
        String text2 = TextClusterableUtil.toText(bytes);

        assertEquals(text, text2);
    }

    @Test
    public void byteArrayCanBeConvertedToDoubleAndViceVersa() {
        // Arrange
        double double1 = new Random().nextDouble();
        byte[] bytes = TextClusterableUtil.toBytes(double1);

        // Act
        double double2 = TextClusterableUtil.toDouble(bytes);

        // Assert
        assertEquals(double1, double2, 0D);
    }

    @Test
    public void doubleArrayCanBeFlattedToByteArrayAndViceVersa() {
        // Arrange
        Random random = new Random();
        double[] doubles = new double[random.nextInt(20) + 1];
        for (int i = 0; i < doubles.length; i++) {
            doubles[i] = random.nextDouble();
        }
        byte[] flattedBytes = TextClusterableUtil.toBytes(doubles);

        // Act
        double[] newDoubles = TextClusterableUtil.toDoubles(flattedBytes);

        // Assert
        assertArrayEquals(doubles, newDoubles, DELTA);
    }

    @Test
    public void textCanBeConvertedToDoubleArrayAndViceVersa() {
        String text = "XXbla blé blõwn∫ çois";

        double[] doubles = TextClusterableUtil.toDoubles(text);

        String text2 = TextClusterableUtil.toText(doubles);

        assertEquals(text, text2);
    }

}
