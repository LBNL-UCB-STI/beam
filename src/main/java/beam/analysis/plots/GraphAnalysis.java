package beam.analysis.plots;

import beam.analysis.BeamAnalysis;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;

public interface GraphAnalysis extends BeamAnalysis {

    void createGraph(IterationEndsEvent event) throws IOException;
}
