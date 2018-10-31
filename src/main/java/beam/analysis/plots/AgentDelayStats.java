package beam.analysis.plots;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.decongestion.handler.DelayAnalysis;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.util.HashMap;
import java.util.Map;

public class AgentDelayStats implements BeamStats, IterationSummaryStats {
    private DelayAnalysis delayAnalysis = new DelayAnalysis();

    public AgentDelayStats(EventsManager eventsManager, Scenario scenario) {
        delayAnalysis.setScenario(scenario);
        if (eventsManager != null) eventsManager.addHandler(delayAnalysis);
    }

    @Override
    public void processStats(Event event) {

    }

    @Override
    public void createGraph(IterationEndsEvent event) {

    }

    @Override
    public void resetStats() {

    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        Map<String, Double> stats = new HashMap<>();
        stats.put("totalVehicleDelay", delayAnalysis.getTotalDelay() / 3600); //unit conversion from sec to hrs
        stats.put("totalTravelTime", delayAnalysis.getTotalTravelTime() / 3600); //unit conversion from sec to hrs
        return stats;
    }
}
