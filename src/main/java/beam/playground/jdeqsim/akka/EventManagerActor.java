package beam.playground.jdeqsim.akka;

import java.util.LinkedList;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsManagerImpl;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;

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
				 System.out.println("number of messages received in total:" + events.size());
				 jdeqsimActorREF.tell("eventsProcessingFinished", getSelf());
			 } else if (s.equalsIgnoreCase("registerJDEQSimREF")){
				 jdeqsimActorREF=getSender();
			 }
        }
        
    }
	
}
