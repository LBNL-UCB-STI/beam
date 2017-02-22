package beam.playground.beamSimAkkaProtoType.scheduler;

import java.util.LinkedList;

public class TriggerAckMessage {

	// TODO: triggerId not really needed for implementation, only for debugging/testing included
	int triggerId;
	int time;
	private LinkedList<TriggerMessage> nextTriggerMessagesToSchedule;

	public LinkedList<TriggerMessage> getNextTriggerMessageToSchedule() {
		return nextTriggerMessagesToSchedule;
	}

	public int getTime() {
		return time;
	}

	public TriggerAckMessage(int triggerId, double time,LinkedList<TriggerMessage> nextTriggerMessagesToSchedule) {
		super();
		this.triggerId = triggerId;
		this.nextTriggerMessagesToSchedule = nextTriggerMessagesToSchedule;
	}

	public int getTriggerId() {
		return triggerId;
	}
	
}
