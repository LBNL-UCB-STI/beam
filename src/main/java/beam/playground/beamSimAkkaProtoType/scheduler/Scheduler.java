package beam.playground.beamSimAkkaProtoType.scheduler;

import java.util.PriorityQueue;
import java.util.SortedSet;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.mobsim.jdeqsim.Message;

import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;

import akka.actor.UntypedActor;
import beam.parking.lib.DebugLib;
import beam.parking.lib.obj.IntegerValueHashMap;
import beam.playground.beamSimAkkaProtoType.GlobalLibAndConfig;
import beam.playground.beamSimAkkaProtoType.beamPersonAgent.ActivityEndMessage;

public class Scheduler extends UntypedActor {
	private PriorityQueue<TriggerMessage> triggers = new PriorityQueue<TriggerMessage>();
	IntegerValueHashMap<Integer> numberOfResponsesPending = new IntegerValueHashMap();
	// key: tick, value: number of pending messages in that tick
	int lastWindowTick;
	private int windowSizeInTicks=GlobalLibAndConfig.getWindowSizeInTicks();
	
	boolean simulationEndReached;



	public Scheduler(Population population) {
		for (Person plan:population.getPersons().values()){
			Activity act=(Activity) plan.getSelectedPlan().getPlanElements().get(0);
			double actEndTime=act.getEndTime();
			triggers.add(new ActivityEndMessage(getSelf(),actEndTime,0));
		}
		lastWindowTick=GlobalLibAndConfig.getTick(triggers.peek().getTime())+windowSizeInTicks;
		
	}

	@Override
	public void onReceive(Object message) throws Throwable {

		if (message instanceof StartSimulationMessage) {
			sendTriggerMessagesWithinWindow();
		} else if (message instanceof TriggerMessage) {
			triggers.add((TriggerMessage) message);
		}else if (message instanceof TriggerAckMessage) {
			TriggerAckMessage triggerAckMessage = (TriggerAckMessage) message;
			numberOfResponsesPending.decrement(GlobalLibAndConfig.getTick(triggerAckMessage.getTime()));
			scheduleTriggerMessage(triggerAckMessage);
			tryToMoveWindowForward();
		} else {
			DebugLib.stopSystemAndReportInconsistency("unexpected message type received:" + message);
		}
	}

	private void scheduleTriggerMessage(TriggerAckMessage triggerAckMessage) {
		// it is important to allow scheduling next message together with triggerAckMessage, as otherwise
		// we might have unprocessed messages outside (before) the window start, which are still not triggered/acknowledged
		if (triggerAckMessage.getNextTriggerMessageToSchedule()!=null){
			triggers.addAll(triggerAckMessage.getNextTriggerMessageToSchedule());
		}
	}

	private void consistencyCheck_noOpenAckMessageAllowedBeforeWindowStart() {
		for (int i = 0; i < lastWindowTick - windowSizeInTicks; i++) {
			if (numberOfResponsesPending.get(i) != 0) {
				DebugLib.stopSystemAndReportInconsistency("no missing ack messages allowed before window start!");
			}
		}
	}

	private void tryToMoveWindowForward() {
		int currenWindowsTick = lastWindowTick;
		for (int i = lastWindowTick - windowSizeInTicks; i <= lastWindowTick; i++) {
			if (numberOfResponsesPending.get(i) == 0) {
				lastWindowTick = currenWindowsTick + i;
			} else {
				break;
			}
		}

		if (lastWindowTick > currenWindowsTick) {
			sendTriggerMessagesWithinWindow();
		}
		
		detectIfSimulationEndReached();

		consistencyCheck_noOpenAckMessageAllowedBeforeWindowStart();
	}

	private void detectIfSimulationEndReached() {
		if (triggers.size()==0){
			simulationEndReached=true;
			for (int i = lastWindowTick - windowSizeInTicks; i <= lastWindowTick; i++) {
				if (numberOfResponsesPending.get(i) != 0) {
					simulationEndReached=false;
					break;
				}
			}
		}
	}

	private void sendTriggerMessagesWithinWindow() {
		while (triggers.peek().getTime() < GlobalLibAndConfig.getTime(lastWindowTick)) {
			TriggerMessage trigger = triggers.poll();
			trigger.getAgentRef().tell(trigger, getSelf());
			numberOfResponsesPending.increment(GlobalLibAndConfig.getTick(trigger.getTime()));
		}
	}
}
