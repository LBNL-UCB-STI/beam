package beam.analysis;

import org.matsim.api.core.v01.events.Event;

public interface BeamAnalysis {
    void processStats(Event event);
    
    void resetStats();
}
