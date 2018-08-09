package beam.playground.jdeqsim.akkaeventsampling.messages;

import org.matsim.api.core.v01.events.Event;

import java.io.Serializable;

/**
 * Created by salma_000 on 6/1/2017.
 */
public class RouterMessageRequest implements IRequest, Serializable {
    Event event;

    public RouterMessageRequest(Event event) {
        this.event = event;
    }

    public Event getEvent() {
        return event;
    }
}
