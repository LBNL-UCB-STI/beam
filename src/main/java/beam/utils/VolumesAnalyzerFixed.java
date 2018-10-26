package beam.utils;

import org.apache.log4j.Logger;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.util.concurrent.TimeUnit;

public class VolumesAnalyzerFixed extends VolumesAnalyzer {
    private final static Logger log = Logger.getLogger(VolumesAnalyzerFixed.class);

    private final int timeBinSize;
    private final int maxTime;
    private final int maxSlotIndex;
    private final int maxHour;

    public VolumesAnalyzerFixed(int timeBinSize, int maxTime, Network network) {
        super(timeBinSize, maxTime, network);
        this.timeBinSize = timeBinSize;
        this.maxTime = maxTime;
        this.maxSlotIndex = (this.maxTime / this.timeBinSize) + 1;
        this.maxHour = (int)TimeUnit.SECONDS.toHours(this.maxTime + 1);
    }
    @Override
    public double[] getVolumesPerHourForLink(final Id<Link> linkId) {
        if (3600.0 % this.timeBinSize != 0) log.error("Volumes per hour and per link probably not correct!");

        double[] volumes = new double[this.maxHour];

        int[] volumesForLink = this.getVolumesForLink(linkId);
        if (volumesForLink == null) return volumes;

        int slotsPerHour = (int)(3600.0 / this.timeBinSize);
        for (int hour = 0; hour < this.maxHour; hour++) {
            double time = hour * 3600.0;
            for (int i = 0; i < slotsPerHour; i++) {
                volumes[hour] += volumesForLink[this.getTimeSlotIndex(time)];
                time += this.timeBinSize;
            }
        }
        return volumes;
    }

    private int getTimeSlotIndex(final double time) {
        if (time > this.maxTime) {
            return this.maxSlotIndex;
        }
        return ((int)time / this.timeBinSize);
    }
}
