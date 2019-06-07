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

public class VehicleMilesTraveledAnalysis implements IterationSummaryAnalysis {
    private Map<String, Double> milesTraveledByVehicleType = new HashMap<>();
    private Set<Id<BeamVehicleType>> vehicleTypes;
    private String humanBodyVehicleType = BeamVehicleType.defaultHumanBodyBeamVehicleType().id().toString();

    public VehicleMilesTraveledAnalysis(Set<Id<BeamVehicleType>> vehicleTypes) {
        this.vehicleTypes = vehicleTypes;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pte = (PathTraversalEvent)event;
            String vehicleType = pte.vehicleType();
            double lengthInMeters = pte.legLength();

            if (!vehicleType.equalsIgnoreCase(humanBodyVehicleType)) {
                milesTraveledByVehicleType.merge(vehicleType, lengthInMeters, (d1, d2) -> d1 + d2);
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
