package beam.playground.beamSimAkkaProtoType.scheduler;

import java.util.LinkedList;

public class TriggerAckMessage {

    //TODO: in order to reduce creation overhead for trigger ack messages, it can be integrated into trigger message just as a flag.

    // TODO: triggerId not really needed for implementation, only for debugging/testing included
    int triggerId;
    double time;
    private LinkedList<TriggerMessage> nextTriggerMessagesToSchedule;

    public TriggerAckMessage(int triggerId, double time, LinkedList<TriggerMessage> nextTriggerMessagesToSchedule) {
        super();
        this.triggerId = triggerId;
        this.time = time;
        this.nextTriggerMessagesToSchedule = nextTriggerMessagesToSchedule;
    }

    public LinkedList<TriggerMessage> getNextTriggerMessageToSchedule() {
        return nextTriggerMessagesToSchedule;
    }

    public double getTime() {
        return time;
    }

    public int getTriggerId() {
        return triggerId;
    }

}
