package beam.analysis.plots;

import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.contrib.decongestion.handler.DelayAnalysis;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class DelayStats implements BeamStats, IterationSummaryStats {

    private static DelayAnalysis delayAnalysis = new DelayAnalysis();


    @Override
    public void processStats(Event event) {
        switch (event.getEventType()) {
            case PersonArrivalEvent.EVENT_TYPE:
                delayAnalysis.handleEvent((PersonArrivalEvent) event);
            case LinkEnterEvent.EVENT_TYPE:
                delayAnalysis.handleEvent((LinkEnterEvent) event);
            case LinkLeaveEvent.EVENT_TYPE:
                delayAnalysis.handleEvent((LinkLeaveEvent) event);
            default:
                // None
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {

    }

    @Override
    public void resetStats() {
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        double totalDelay = delayAnalysis.getTotalDelay() / 3600.0;
        Map<String, Double > res = new HashMap<>();
        res.put("VehicleDelay", totalDelay);
        return res;
    }
}
