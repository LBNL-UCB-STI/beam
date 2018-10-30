package beam.analysis.plots;

import beam.agentsim.events.PathTraversalEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class VehicleTravelTimeStats implements BeamStats, IterationSummaryStats{
    private Map<String, Double> secondsTraveledByVehicleType = new HashMap<>();

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String vehicleType = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
            double hoursTraveled = (Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)) -
                    Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME)));

            secondsTraveledByVehicleType.merge(vehicleType, hoursTraveled, (d1, d2) -> d1 + d2);
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) {

    }

    @Override
    public void resetStats() {
        secondsTraveledByVehicleType.clear();
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        return secondsTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleHoursTraveled_" + e.getKey(),
                e -> e.getValue()/3600.0
        )); 
    }
}
