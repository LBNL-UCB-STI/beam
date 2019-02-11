package beam.utils.logging;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.charset.StandardCharsets;

final class TextClusterableUtil {

    private TextClusterableUtil() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    static String toText(final byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String toText(final double[] doubles) {
        return new String(toBytes(doubles), StandardCharsets.UTF_8).trim();
    }

    static byte[] toBytes(final String txt) {
        return txt.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] toBytes(double doubleValue) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(Double.BYTES);
        byteBuffer.putDouble(doubleValue);
        return byteBuffer.array();
    }

    static byte[] toBytes(final double[] doubles) {
        byte[] result = new byte[doubles.length * Double.BYTES];

        int counter = 0;
        for (double d : doubles) {
            byte[] newByte = toBytes(d);
            System.arraycopy(newByte, 0, result, counter, Double.BYTES);
            counter += Double.BYTES;
        }

        return result;
    }

    static double toDouble(byte[] bytes) {
        return ByteBuffer.wrap(bytes).asDoubleBuffer().get();
    }

    static double[] toDoubles(final byte[] bytesInput) {
        int newSize = (int) (Math.ceil(bytesInput.length * 1D / Double.BYTES));

        byte[] bytes = new byte[newSize * Double.BYTES];
        System.arraycopy(bytesInput, 0, bytes, 0, bytesInput.length);

        DoubleBuffer wrap = ByteBuffer.wrap(bytes).asDoubleBuffer();
        double[] doubles = new double[wrap.remaining()];
        wrap.get(doubles);

        return doubles;
    }

    static double[] toDoubles(final String text) {
        return toDoubles(toBytes(text));
    }

}
