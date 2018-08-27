package beam.playground.jdeqsim.akka;

import akka.actor.ActorRef;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.handler.EventHandler;

public class AkkaEventHandlerAdapter implements EventsManager {

    private ActorRef eventHandlerActorREF;

    public AkkaEventHandlerAdapter(ActorRef eventHandlerActorREF) {
        this.eventHandlerActorREF = eventHandlerActorREF;
    }

    @Override
    public void processEvent(Event event) {
        eventHandlerActorREF.tell(event, ActorRef.noSender());
    }

    @Override
    public void addHandler(EventHandler handler) {
        // TODO Auto-generated method stub

    }

    @Override
    public void removeHandler(EventHandler handler) {
        // TODO Auto-generated method stub

    }

    @Override
    public void resetHandlers(int iteration) {
        // TODO Auto-generated method stub

    }

    @Override
    public void initProcessing() {
        // TODO Auto-generated method stub

    }

    @Override
    public void afterSimStep(double time) {
        // TODO Auto-generated method stub

    }

    @Override
    public void finishProcessing() {
        eventHandlerActorREF.tell("lastMessage", ActorRef.noSender());
//		Timeout timeout = new Timeout(FiniteDuration.create(10, java.util.concurrent.TimeUnit.SECONDS));
//	    Future<Object> future = Patterns.ask(eventHandlerActorREF, "lastMessage", timeout);
//	    try {
//			String result = (String) Await.result(future, timeout.duration());
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		//eventHandlerActorREF.tell("lastMessage", ActorRef.noSender());
    }

}
