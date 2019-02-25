package beam.utils;

import org.apache.log4j.Logger;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.util.concurrent.TimeUnit;

public class VolumesAnalyzerFixed extends VolumesAnalyzer {
    private final static Logger log = Logger.getLogger(VolumesAnalyzerFixed.class);

    private static final int SECONDS_PER_HOUR = (int) TimeUnit.HOURS.toSeconds(1L);

    private final int timeBinSizeInSeconds;
    private final int maxSlotIndex;
    private final int maxHour;

    public VolumesAnalyzerFixed(int timeBinSizeInSeconds, int maxTime, Network network) {
        super(timeBinSizeInSeconds, maxTime, network);
        this.timeBinSizeInSeconds = timeBinSizeInSeconds;
        this.maxSlotIndex = (maxTime / timeBinSizeInSeconds) + 1;
        this.maxHour = (int) TimeUnit.SECONDS.toHours(maxTime + 1);
    }

    @Override
    public double[] getVolumesPerHourForLink(final Id<Link> linkId) {
        if (SECONDS_PER_HOUR % timeBinSizeInSeconds != 0) {
            log.error("Volumes per hour and per link probably not correct!. TimeBinSize: " + timeBinSizeInSeconds);
        }

        double[] volumes = new double[maxHour];

        int[] volumesForLink = getVolumesForLink(linkId);
        if (volumesForLink == null) {
            return volumes;
        }

        final int slotsPerHour = SECONDS_PER_HOUR / timeBinSizeInSeconds;
        int slotIndex = 0;
        for (int hour = 0; hour < maxHour; hour++) {
            for (int i = 0; i < slotsPerHour; i++) {
                volumes[hour] += volumesForLink[Math.min(maxSlotIndex, slotIndex++)];
            }
        }

        return volumes;
    }

}
