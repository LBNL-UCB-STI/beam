package beam.analysis.plots;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;

public interface BeamStats {
    void processStats(Event event);

    void createGraph(IterationEndsEvent event) throws IOException;

    void resetStats();
}
