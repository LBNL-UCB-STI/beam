package beam.playground.jdeqsimPerformance;

import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;

public class LogEnterLinkEvents implements LinkEnterEventHandler {


    @Override
    public void reset(int iteration) {
        // TODO Auto-generated method stub
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        // TODO Use a writer to Log all the details of an event to a CSV file
        System.out.println(event.toString());
    }

}