package beam.playground.jdeqsim.akkaeventsampling;

import akka.actor.UntypedActor;
import beam.playground.jdeqsim.akkaeventsampling.messages.SchedulerActorMessageRequest;
import org.matsim.api.core.v01.events.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SchedulerActor extends UntypedActor {

    private static final Logger log = LoggerFactory.getLogger(SchedulerActor.class);

    private List<Event> buffer = new ArrayList<>();

    @Override
    public void onReceive(Object message) throws Throwable {
        if (message instanceof SchedulerActorMessageRequest) {
            log.debug("Before Copy size" + Dictionary.eventList.size());
            buffer = new ArrayList<>(Dictionary.eventList);
            log.debug("Buffer size" + buffer.size());
            Dictionary.eventList.clear();
            log.debug("After Copy size" + Dictionary.eventList.size());

        } else {
            unhandled(message);
        }
    }
}
