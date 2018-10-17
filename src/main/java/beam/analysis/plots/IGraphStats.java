package beam.analysis.plots;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;

public interface IGraphStats {
    void processStats(Event event);

    void createGraph(IterationEndsEvent event) throws IOException;

    void createGraph(IterationEndsEvent event, String graphType) throws IOException;

    void resetStats();

    default void collectEvents(Event event){
        System.out.println("Does not need to be overridden by everyone");
    }
}
