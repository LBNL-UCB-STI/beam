package beam.physsim.jdeqsim.akka;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import org.matsim.api.core.v01.events.Event;

import java.util.LinkedList;

public class EventManagerActor extends UntypedActor {

	ActorRef jdeqsimActorREF;
	
	LinkedList<Event> events=new LinkedList<>();
	//EventsManager eventsManager=new EventsManagerImpl();
	
	@Override
    public void onReceive(Object msg) throws Exception {
        if(msg instanceof Event) {
        	events.add((Event) msg);
        	//TODO: could call eventsManager here, if needed 
        } else if (msg instanceof String){
        	String s=(String) msg;
			 if (s.equalsIgnoreCase("lastMessage")){
				 jdeqsimActorREF.tell("eventsProcessingFinished", getSelf());
			 } else if (s.equalsIgnoreCase("registerJDEQSimREF")){
				 jdeqsimActorREF=getSender();
			 }
        }
        
    }

	public static Props props(){
		return  Props.create(EventManagerActor.class);
	}
	
}
