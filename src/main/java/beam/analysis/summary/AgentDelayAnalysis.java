package beam.analysis.summary;

import beam.analysis.DelayMetricAnalysis;
import beam.analysis.IterationSummaryAnalysis;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.util.HashMap;
import java.util.Map;

public class AgentDelayAnalysis implements IterationSummaryAnalysis {
    private DelayMetricAnalysis delayMetricAnalysis;

    public AgentDelayAnalysis(EventsManager eventsManager, Scenario scenario , OutputDirectoryHierarchy controlerIO , BeamServices services, BeamConfig beamConfig) {
        delayMetricAnalysis = new DelayMetricAnalysis(eventsManager, controlerIO , services , services.networkHelper(), beamConfig );
        if (eventsManager != null) eventsManager.addHandler(delayMetricAnalysis);
    }

    @Override
    public void processStats(Event event) {

    }

    @Override
    public void resetStats() {

    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> stats = new HashMap<>();
        stats.put("totalVehicleDelay" , delayMetricAnalysis.getTotalDelay()/ 3600); //unit conversion from sec to hrs
        stats.put("totalTravelTime", delayMetricAnalysis.totalTravelTime() / 3600); //unit conversion from sec to hrs
        return stats;
    }
}
