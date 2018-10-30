package beam.analysis.plots;

import beam.agentsim.events.PathTraversalEvent;
import com.google.common.base.CaseFormat;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class VehicleMilesTraveledStats implements BeamStats, IterationSummaryStats{
    private Map<String, Double> milesTraveledByVehicleType = new HashMap<>();

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String vehicleType = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
            double lengthInMeters = Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH));

            milesTraveledByVehicleType.merge(vehicleType, lengthInMeters, (d1, d2) -> d1 + d2);
            milesTraveledByVehicleType.merge("total", lengthInMeters, (d1, d2) -> d1 + d2);

        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) {

    }

    @Override
    public void resetStats() {
        milesTraveledByVehicleType.clear();
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        return milesTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleMilesTraveled_" + e.getKey(),
                e -> e.getValue() * 0.000621371192 // unit conversion from meters to miles
        )); 
    }
}
