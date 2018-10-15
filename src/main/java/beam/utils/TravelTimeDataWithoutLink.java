package beam.utils;

public class TravelTimeDataWithoutLink {
    public final double[] timeSum;
    public final int[] timeCnt;
    public final double[] travelTimes;

    public TravelTimeDataWithoutLink(final double[] timeSum, final int[] timeCnt, final double[] travelTimes) {
        this.timeSum = timeSum;
        this.timeCnt = timeCnt;
        this.travelTimes = travelTimes;
    }
}