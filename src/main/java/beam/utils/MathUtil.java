package beam.utils;

/**
 * @Author mygreencar.
 */
public class MathUtil {
    public static Double roundUpToNearestInterval(double num, double interval){
        return Math.floor(num/interval)*interval + Math.ceil((num / interval) - Math.floor(num / interval))*interval;
    }
}
