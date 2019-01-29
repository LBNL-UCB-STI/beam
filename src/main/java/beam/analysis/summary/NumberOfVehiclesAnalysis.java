package beam.analysis.summary;

import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.sim.BeamServices;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import scala.collection.JavaConverters;

public class NumberOfVehiclesAnalysis implements IterationSummaryAnalysis{
    private Map<String, Integer> numberOfVehiclesByType = new HashMap<>();
    private HashSet<String> uniqueVehicleIds = new HashSet<>();
    private BeamServices beamServices;

    public NumberOfVehiclesAnalysis(BeamServices services){
       beamServices = services;
    }
    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String vehicleId = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
            if (uniqueVehicleIds.add(vehicleId)) {
                    String vehicleType = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
                    numberOfVehiclesByType.merge(vehicleType, 1, (d1, d2) -> d1 + d2);
                }
        }
    }

    @Override
    public void resetStats() {
        numberOfVehiclesByType.clear();
        uniqueVehicleIds.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        JavaConverters.mapAsJavaMap(beamServices.transitFleetSizes()).forEach((k,v) -> numberOfVehiclesByType.put(k,v));
        return numberOfVehiclesByType.entrySet().stream().collect(Collectors.toMap(
                e -> "numberOfVehicles_" + e.getKey(),
                e -> e.getValue().doubleValue()
        ));
    }

}
