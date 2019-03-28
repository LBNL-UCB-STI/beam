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
    private String humanBodyVehicleType = BeamVehicleType.defaultHumanBodyBeamVehicleType().toString();

    public VehicleMilesTraveledAnalysis(Set<Id<BeamVehicleType>> vehicleTypes) {
        this.vehicleTypes = vehicleTypes;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pte = (PathTraversalEvent)event;
            String vehicleType = pte.vehicleType();
            double lengthInMeters = pte.legLength();

            milesTraveledByVehicleType.merge(vehicleType, lengthInMeters, (d1, d2) -> d1 + d2);
            if (isMotorizedMode(vehicleType)) {
                milesTraveledByVehicleType.merge("total", lengthInMeters, (d1, d2) -> d1 + d2);
            }

        }
    }

    private boolean isMotorizedMode(String vehicleType){
        return !vehicleType.equalsIgnoreCase(BeamVehicleType.defaultHumanBodyBeamVehicleType().id().toString()) && !vehicleType.equalsIgnoreCase(BeamVehicleType.defaultBicycleBeamVehicleType().id().toString());
    }

    private String getTitle(String key){
        String prefix=null;
        if (key.contains("total")){
            prefix= "motorizedVehicleMilesTraveled_";
        } else {
            prefix= "vehicleMilesTraveled_";
        }

        return prefix + key;
    }

    @Override
    public void resetStats() {
        milesTraveledByVehicleType.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> result = milesTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> getTitle(e.getKey()),
                e -> e.getValue() * 0.000621371192 // unit conversion from meters to miles
        ));

        vehicleTypes.foreach(vt -> result.merge("vehicleMilesTraveled_" + vt.toString(),0D, (d1, d2) -> d1 + d2));

        return result;
    }
}
