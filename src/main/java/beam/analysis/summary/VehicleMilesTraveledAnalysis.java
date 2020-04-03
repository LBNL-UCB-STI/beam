package beam.analysis.summary;

import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import scala.collection.Set;
import scala.collection.Set$;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class VehicleMilesTraveledAnalysis implements IterationSummaryAnalysis {

    private static final String HUMAN_BODY_VEHICLE_TYPE = "BODY-TYPE-DEFAULT";
    private static final double METER_TO_MILE_CONVERTER_CONST = 0.000621371192D;

    private final Map<String, Double> milesTraveledByVehicleType = new HashMap<>();
    private final Set<Id<BeamVehicleType>> vehicleTypes;

    public VehicleMilesTraveledAnalysis(Set<Id<BeamVehicleType>> vehicleTypes) {
        this.vehicleTypes = vehicleTypes == null
                ? Set$.MODULE$.empty()
                : vehicleTypes;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pte = (PathTraversalEvent) event;
            String vehicleType = pte.vehicleType();
            double lengthInMeters = pte.legLength();

            if (!vehicleType.equalsIgnoreCase(HUMAN_BODY_VEHICLE_TYPE)) {
                milesTraveledByVehicleType.merge(vehicleType, lengthInMeters, Double::sum);
                milesTraveledByVehicleType.merge("total", lengthInMeters, Double::sum);
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
                e -> e.getValue() * METER_TO_MILE_CONVERTER_CONST
        ));

        vehicleTypes.foreach(vt -> result.put("vehicleMilesTraveled_" + vt.toString(), milesTraveledByVehicleType.getOrDefault(vt.toString(), 0.0) * METER_TO_MILE_CONVERTER_CONST));

        return result;
    }
}
