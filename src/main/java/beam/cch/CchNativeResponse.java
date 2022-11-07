package beam.cch;

import java.util.List;

public class CchNativeResponse {
    private List<String> links;
    private double distance;
    private long depTime;
    private List<String> times;

    public CchNativeResponse() {
    }

    public List<String> getLinks() {
        return links;
    }

    public List<String> getTimes() {
        return times;
    }

    public double getDistance() {
        return distance;
    }

    public long getDepTime() {
        return depTime;
    }
}
