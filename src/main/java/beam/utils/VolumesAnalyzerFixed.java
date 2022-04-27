package beam.utils;

import beam.agentsim.agents.vehicles.BeamVehicle;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class VolumesAnalyzerFixed extends VolumesAnalyzer {
    private final static Logger log = LoggerFactory.getLogger(VolumesAnalyzerFixed.class);

    private static final int SECONDS_PER_HOUR = (int) TimeUnit.HOURS.toSeconds(1L);

    private final int timeBinSizeInSeconds;
    private final int maxSlotIndex;
    private final int maxHour;
    private Map<Id<BeamVehicle>, BeamVehicle> vehicleMap;

    public VolumesAnalyzerFixed(int timeBinSizeInSeconds, int maxTime, Network network, Map<Id<BeamVehicle>, BeamVehicle> vehicleMap) {
        super(timeBinSizeInSeconds, maxTime, network);
        this.timeBinSizeInSeconds = timeBinSizeInSeconds;
        this.vehicleMap = vehicleMap;
        this.maxSlotIndex = (maxTime / timeBinSizeInSeconds) + 1;
        this.maxHour = (int) TimeUnit.SECONDS.toHours(maxTime + 1);
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        if (vehicleMap != null) {
            BeamVehicle vehicle = vehicleMap.get(event.getVehicleId());
            String category = vehicleCategory(vehicle);

            VehicleEntersTrafficEvent eventWithMode = new VehicleEntersTrafficEvent(event.getTime(), event.getPersonId(),
                    event.getLinkId(), event.getVehicleId(), category, event.getRelativePositionOnLink());
            super.handleEvent(eventWithMode);
        } else {
            super.handleEvent(event);
        }
    }

    private String vehicleCategory(BeamVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        return vehicle.beamVehicleType().vehicleCategory().toString();
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

    public double[] getVolumesPerHourForLink(final Id<Link> linkId, String mode) {
        if (SECONDS_PER_HOUR % timeBinSizeInSeconds != 0)
            log.error("Volumes per hour and per link probably not correct! TimeBinSize: " + timeBinSizeInSeconds);

        double[] volumes = new double[maxHour];

        int[] volumesForLink = this.getVolumesForLink(linkId, mode);
        if (volumesForLink == null) return volumes;

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
