package beam.analysis.summary;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.analysis.NumberOfVehiclesAnalysisUtil;
import beam.sim.BeamScenario;
import org.matsim.api.core.v01.events.Event;
import scala.collection.JavaConverters;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

public class NumberOfVehiclesAnalysis implements IterationSummaryAnalysis{
    private Map<String, Integer> numberOfVehiclesByType = new HashMap<>();
    private HashSet<String> uniqueVehicleIds = new HashSet<>();
    private BeamScenario beamScenario;

    public NumberOfVehiclesAnalysis(BeamScenario beamScenario) {
        this.beamScenario = beamScenario;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pte = (PathTraversalEvent)event;
            String vehicleId = pte.vehicleId().toString();
            if(uniqueVehicleIds.add(vehicleId)) {
                String vehicleType = pte.vehicleType();
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
        scala.collection.mutable.Map<String, Integer> transitFleetSizes = NumberOfVehiclesAnalysisUtil.estimateInUseFleet(beamScenario.transportNetwork());
        JavaConverters.mapAsJavaMap(transitFleetSizes).forEach((k,v) -> numberOfVehiclesByType.put(k,v));
        return numberOfVehiclesByType.entrySet().stream().collect(Collectors.toMap(
                e -> "numberOfVehicles_" + e.getKey(),
                e -> e.getValue().doubleValue()
        ));
    }

}
