package beam.analysis.stats;

import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.Map;

public abstract class CSVStats implements EventHandler {

    public CSVStats(EventsManager eventsManager){
        eventsManager.addHandler(this);
    }

    public abstract Map<String,Double> getIterationSummaryStats();
}
