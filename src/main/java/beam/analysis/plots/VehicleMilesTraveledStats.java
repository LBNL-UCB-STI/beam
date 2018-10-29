package beam.analysis.plots;

import beam.agentsim.events.PathTraversalEvent;
import com.google.common.base.CaseFormat;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class VehicleMilesTraveledStats implements BeamStats, IterationSummaryStats{
    private Map<String, Double> modeMilesTraveled = new HashMap<>();

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            processVehicleMilesTraveled(event);
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) {

    }

    @Override
    public void resetStats() {
        modeMilesTraveled.clear();
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        return modeMilesTraveled.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleMilesTraveled" + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, e.getKey()),
                e -> e.getValue() * 0.000621371192
        )); 
    }

    private void processVehicleMilesTraveled(Event event) {
        Map<String, String> eventAttributes = event.getAttributes();
        String originalMode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
        double lengthInMeters = Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH));

        modeMilesTraveled.merge(originalMode, lengthInMeters, (d1, d2) -> d1 + d2);
    }
}
