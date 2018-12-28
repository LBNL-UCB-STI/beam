package beam.analysis.summary;

import beam.agentsim.agents.vehicles.BeamVehicle;
import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import scala.collection.Set;
import scala.collection.concurrent.TrieMap;
import scala.collection.immutable.List;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class VehicleTravelTimeAnalysis implements IterationSummaryAnalysis {
    private Map<String, Double> secondsTraveledByVehicleType = new HashMap<>();
    private Set<Id<BeamVehicleType>> vehicleTypes;

    public VehicleTravelTimeAnalysis(Set<Id<BeamVehicleType>> vehicleTypes) {
        this.vehicleTypes = vehicleTypes;
    }

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
    public void resetStats() {
        secondsTraveledByVehicleType.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> result = secondsTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleHoursTraveled_" + e.getKey(),
                e -> e.getValue()/3600.0
        ));

        vehicleTypes.foreach(vt -> result.merge("vehicleHoursTraveled_" + vt.toString(),0D, (d1, d2) -> d1 + d2));
        return result;
    }
}
