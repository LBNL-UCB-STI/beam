package beam.playground.beamSimAkkaProtoType.scheduler;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import beam.playground.beamSimAkkaProtoType.GlobalLibAndConfig;
import beam.playground.beamSimAkkaProtoType.beamPersonAgent.ActivityEndMessage;
import beam.playground.beamSimAkkaProtoType.beamPersonAgent.BeamPersonAgent;
import beam.utils.DebugLib;
import beam.utils.IntegerValueHashMap;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.PriorityQueue;

public class Scheduler extends UntypedActor {
	
	LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	
	private PriorityQueue<TriggerMessage> triggers = new PriorityQueue<TriggerMessage>();
	IntegerValueHashMap<Integer> numberOfResponsesPending = new IntegerValueHashMap();
	// key: tick, value: number of pending messages in that tick
	
	
	//private int lastWindowTick;
	
	private int windowStartTick;
	
	private int windowSizeInTicks=GlobalLibAndConfig.getWindowSizeInTicks();
	
	boolean simulationEndReached;



	public Scheduler(Population population, ActorRef chargingInfrastructureManager) {
		int i=0;
		for (Person person:population.getPersons().values()){
			Activity act=(Activity) person.getSelectedPlan().getPlanElements().get(0);
			double actEndTime=act.getEndTime();
			ActorRef personRef = getContext().actorOf(Props.create(BeamPersonAgent.class,person.getSelectedPlan(),chargingInfrastructureManager,getSelf()),"beamPersonAgent-"+i++);
			
			triggers.add(new ActivityEndMessage(personRef,actEndTime,0));
		}
		
		setWindowStartTick(GlobalLibAndConfig.getTick(triggers.peek().getTime()));
	}

	@Override
	public void onReceive(Object message) throws Throwable {
		GlobalLibAndConfig.printMessage(log, message);
		updateStats(message);
		if (message instanceof StartSimulationMessage) {
			sendTriggerMessagesWithinWindow();
		} else if (message instanceof TriggerMessage) {
			triggers.add((TriggerMessage) message);
		}else if (message instanceof TriggerAckMessage) {
			TriggerAckMessage triggerAckMessage = (TriggerAckMessage) message;
			processTriggerAck(triggerAckMessage);
			scheduleTriggerMessage(triggerAckMessage);
			tryToMoveWindowForward();
		} else {
			DebugLib.stopSystemAndReportInconsistency("unexpected message type received:" + message);
		}
	}

	private int stats_numberOfTriggerMessages=0;
	private int stats_numberOfTriggerMessagesModulo=1;
	private void updateStats(Object message) {
		if (message instanceof TriggerAckMessage){
			stats_numberOfTriggerMessages++;
			if (stats_numberOfTriggerMessages%stats_numberOfTriggerMessagesModulo==0){
				log.info("numberOfTriggerMessages processed: " + stats_numberOfTriggerMessages);
				stats_numberOfTriggerMessagesModulo*=2;
			}
		}
	}

	private void processTriggerAck(TriggerAckMessage triggerAckMessage) {
		if (GlobalLibAndConfig.getTick(triggerAckMessage.getTime())<getWindowStartTick()){
			DebugLib.stopSystemAndReportInconsistency("ack message received, which was before window start:" + triggerAckMessage);
		}
		
		numberOfResponsesPending.decrement(GlobalLibAndConfig.getTick(triggerAckMessage.getTime()));
	}

	private void scheduleTriggerMessage(TriggerAckMessage triggerAckMessage) {
		// it is important to allow scheduling next message together with triggerAckMessage, as otherwise
		// we might have unprocessed messages outside (before) the window start, which are still not triggered/acknowledged
		if (triggerAckMessage.getNextTriggerMessageToSchedule()!=null){
			for (TriggerMessage triggerMessage:triggerAckMessage.getNextTriggerMessageToSchedule()){
				try{
				triggers.add(triggerMessage);
				} catch (RuntimeException e){
					System.out.println(e.getMessage());
				}
			}
		}
	}

	private void consistencyCheck_noOpenAckMessageAllowedBeforeWindowStart() {
		for (int i = 0; i < getWindwStartTick(); i++) {
			if (numberOfResponsesPending.get(i) != 0) {
				DebugLib.stopSystemAndReportInconsistency("no missing ack messages allowed before window start!");
			}
		}
	}

	private int getWindwStartTick() {
		return getLastWindowTick() - windowSizeInTicks;
	}

	private void tryToMoveWindowForward() {
		
		
		for (int i = getWindwStartTick(); i <= getLastWindowTick(); i++) {
			if (numberOfResponsesPending.get(i) == 0) {
				sendTriggerMessagesWithinWindow();
				setWindowStartTick(i);
			} else {
				break;
			}
		}

		
		detectIfSimulationEndReached();

		consistencyCheck_noOpenAckMessageAllowedBeforeWindowStart();
	}

	private void detectIfSimulationEndReached() {
		if (triggers.size()==0){
			//log.info("getNumberOfPendingAckMessages():"+getNumberOfPendingAckMessages());
			simulationEndReached=true;
			for (int i = getWindwStartTick(); i <= getLastWindowTick(); i++) {
				if (numberOfResponsesPending.get(i) != 0) {
					
					simulationEndReached=false;
					break;
				}
			}
		}
		
		
		if (simulationEndReached){
			log.info("end of simulation reached");
		}
	}

	private void sendTriggerMessagesWithinWindow() {
		while (triggers.size()>0 && triggers.peek().getTime() <= GlobalLibAndConfig.getTime(getLastWindowTick())) {
			TriggerMessage trigger = triggers.poll();
			trigger.getAgentRef().tell(trigger, getSelf());
			numberOfResponsesPending.increment(GlobalLibAndConfig.getTick(trigger.getTime()));
			//consistencyCheck_noOpenAckMessageAllowedBeforeWindowStart();
		}
	}

	public int getLastWindowTick() {
		return getWindowStartTick()+windowSizeInTicks-1;
	}

	public int getWindowStartTick() {
		return windowStartTick;
	}

	public void setWindowStartTick(int windowStartTick) {
		this.windowStartTick = windowStartTick;
	}
	
	public int getNumberOfPendingAckMessages(){
		int numberOfPendingAckMessages=0;
		
		for (int i = 0; i < getLastWindowTick(); i++) {
			numberOfPendingAckMessages+=numberOfResponsesPending.get(i);
		}
		
		return numberOfPendingAckMessages;
	}
}
