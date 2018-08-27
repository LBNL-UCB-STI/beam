package beam.playground.jdeqsim.akkaeventsampling;

import akka.actor.UntypedActor;
import beam.playground.jdeqsim.akkaeventsampling.messages.WorkerMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by salma_000 on 6/1/2017.
 */
public class Worker extends UntypedActor {

    public static final String ACTOR_NAME = "Worker";

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    @Override
    public void onReceive(Object message) throws Throwable {
        if (message instanceof WorkerMessageRequest) {
            WorkerMessageRequest msg = (WorkerMessageRequest) message;
            // TODO: 6/2/2017 get event from message and pu this message into dictionary
            Dictionary.eventList.add(msg.getRouterMessage().getEvent());
            //log.debug("Worker actor message received"+Dictionary.eventList.size());
        }
    }
}
