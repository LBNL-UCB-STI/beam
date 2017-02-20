package beam.utils;

public class MathUtil {
    public static Double roundUpToNearestInterval(double num, double interval){
        return Math.floor(num/interval)*interval + Math.ceil((num / interval) - Math.floor(num / interval))*interval;
    }

    public static double roundDownToNearestInterval(double num, double interval) {
        return Math.floor(num/interval)*interval + Math.floor((num / interval) - Math.floor(num / interval))*interval;
    }
}
