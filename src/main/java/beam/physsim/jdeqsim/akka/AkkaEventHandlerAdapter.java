package beam.physsim.jdeqsim.akka;

import akka.actor.ActorRef;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.utils.collections.Tuple;

public class AkkaEventHandlerAdapter implements EventsManager {

	private ActorRef eventHandlerActorREF;

	public AkkaEventHandlerAdapter(ActorRef eventHandlerActorREF){
		this.eventHandlerActorREF = eventHandlerActorREF;
	}
	
	@Override
	public void processEvent(Event event) {
		eventHandlerActorREF.tell(event, ActorRef.noSender());
	}

	@Override
	public void addHandler(EventHandler handler) {
		//eventHandlerActorREF.tell(handler, ActorRef.noSender());
		DebugLib.stopSystemAndReportMethodWhichShouldNeverHaveBeenCalled();
	}

	@Override
	public void removeHandler(EventHandler handler) {
		DebugLib.stopSystemAndReportMethodWhichShouldNeverHaveBeenCalled();
	}

	@Override
	public void resetHandlers(int iteration) {
		DebugLib.stopSystemAndReportMethodWhichShouldNeverHaveBeenCalled();
	}

	@Override
	public void initProcessing() {
		DebugLib.stopSystemAndReportMethodWhichShouldNeverHaveBeenCalled();
	}

	@Override
	public void afterSimStep(double time) {
		DebugLib.stopSystemAndReportMethodWhichShouldNeverHaveBeenCalled();
	}

	@Override
	public void finishProcessing() {
		eventHandlerActorREF.tell(EventManagerActor.LAST_MESSAGE, ActorRef.noSender());
	}

}
