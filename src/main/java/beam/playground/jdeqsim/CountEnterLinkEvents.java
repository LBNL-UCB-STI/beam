package beam.playground.jdeqsim;

import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;

public class CountEnterLinkEvents implements LinkEnterEventHandler {

    private int linkEnterCount;

    public int getLinkEnterCount() {
        return linkEnterCount;
    }

    @Override
    public void reset(int iteration) {
        // TODO Auto-generated method stub
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        linkEnterCount++;
    }

}