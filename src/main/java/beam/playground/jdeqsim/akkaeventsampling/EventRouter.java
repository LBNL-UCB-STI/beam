package beam.playground.jdeqsim.akkaeventsampling;

import akka.actor.*;
import akka.routing.RoundRobinPool;
import beam.playground.jdeqsim.akkaeventsampling.messages.RouterMessageRequest;
import beam.playground.jdeqsim.akkaeventsampling.messages.WorkerMessageRequest;
import scala.concurrent.duration.Duration;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class EventRouter extends UntypedActor {
    public static final String ACTOR_NAME = "EventRouter";
    private ActorRef worker;

    public void preStart() throws Exception {
        SupervisorStrategy supervisorStrategy = new OneForOneStrategy(5, Duration.create(1, TimeUnit.MINUTES), Collections.<Class<? extends Throwable>>singletonList(Exception.class));
        worker = getContext().actorOf(new RoundRobinPool(10).withSupervisorStrategy(supervisorStrategy).props(Props.create(Worker.class)), Worker.ACTOR_NAME);
    }

    @Override
    public void onReceive(Object message) throws Throwable {
        if (message instanceof RouterMessageRequest) {
            RouterMessageRequest msg = (RouterMessageRequest) message;
            worker.forward(new WorkerMessageRequest(msg), getContext());
        }
    }
}
