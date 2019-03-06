package beam.analysis.summary;

import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.sim.BeamServices;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import scala.collection.Set;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class VehicleMilesTraveledAnalysis implements IterationSummaryAnalysis {
    private Map<String, Double> milesTraveledByVehicleType = new HashMap<>();
    private Set<Id<BeamVehicleType>> vehicleTypes;
    private BeamServices beamServices;

    public VehicleMilesTraveledAnalysis(Set<Id<BeamVehicleType>> vehicleTypes, BeamServices beamServices) {
        this.vehicleTypes = vehicleTypes;
        this.beamServices = beamServices;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pte = (PathTraversalEvent)event;
            String vehicleType = pte.vehicleType();
            double lengthInMeters = pte.legLength();

            milesTraveledByVehicleType.merge(vehicleType, lengthInMeters, (d1, d2) -> d1 + d2);

            Id<BeamVehicleType> bodyVehicleTypeId = Id.create("BODY-TYPE-DEFAULT", BeamVehicleType.class);

            if (!vehicleType.equalsIgnoreCase(beamServices.vehicleTypes().get(bodyVehicleTypeId).get().toString())) {
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
