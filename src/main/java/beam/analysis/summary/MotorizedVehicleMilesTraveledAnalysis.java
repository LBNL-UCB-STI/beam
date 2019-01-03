package beam.analysis.summary;

import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import scala.collection.Set;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class MotorizedVehicleMilesTraveledAnalysis implements IterationSummaryAnalysis {
    private Map<String, Double> milesTraveledByVehicleType = new HashMap<>();
    private Set<Id<BeamVehicleType>> vehicleTypes;

    public MotorizedVehicleMilesTraveledAnalysis(Set<Id<BeamVehicleType>> vehicleTypes) {
        this.vehicleTypes = vehicleTypes;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String vehicleType = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
            double lengthInMeters = Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH));

            milesTraveledByVehicleType.merge(vehicleType, lengthInMeters, (d1, d2) -> d1 + d2);
            if (!vehicleType.equalsIgnoreCase(BeamVehicleType.BODY_TYPE_DEFAULT()) && !vehicleType.equalsIgnoreCase(BeamVehicleType.BIKE_TYPE_DEFAULT())) {
                milesTraveledByVehicleType.merge("total", lengthInMeters, (d1, d2) -> d1 + d2);
            }

        }
    }
    @Override
    public void resetStats() {
        milesTraveledByVehicleType.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> result = milesTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "motorizedVehicleMilesTraveled_" + e.getKey(),
                e -> e.getValue() * 0.000621371192 // unit conversion from meters to miles
        ));

        vehicleTypes.foreach(vt -> result.merge("vehicleMilesTraveled_" + vt.toString(),0D, (d1, d2) -> d1 + d2));

        return result;
    }
}
