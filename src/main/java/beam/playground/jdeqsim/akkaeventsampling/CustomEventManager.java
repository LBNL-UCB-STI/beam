package beam.playground.jdeqsim.akkaeventsampling;

import akka.actor.ActorRef;
import beam.playground.jdeqsim.akkaeventsampling.messages.RouterMessageRequest;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.EventsManagerImpl;

public class CustomEventManager extends EventsManagerImpl {

    private final ActorRef eventRouter;

    public CustomEventManager(ActorRef eventRouter) {
        this.eventRouter = eventRouter;
    }

    @Override
    public void processEvent(final Event event) {
        this.eventRouter.tell(new RouterMessageRequest(event), ActorRef.noSender());
        super.processEvent(event);
    }

}
