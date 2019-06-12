package beam.playground.beamSimAkkaProtoType.scheduler;

import akka.actor.ActorRef;
import beam.playground.beamSimAkkaProtoType.GlobalLibAndConfig;

import java.util.LinkedList;

public class TriggerMessage implements Comparable<TriggerMessage> {

    private static int triggerIdCounter = 0;
    private double time;
    //TODO: triggerId can be removed (not needed for algorithm implementation), but might be useful for debugging/testing
    private int triggerId;
    private int priority;
    private ActorRef agentRef;

    public TriggerMessage(ActorRef agentRef, double time, int priority) {
        this.agentRef = agentRef;
        this.time = time;
        this.priority = priority;
        this.triggerId = triggerIdCounter++;
    }

    public double getTime() {
        return time;
    }

    public int getPriority() {
        return priority;
    }

    public int getTriggerId() {
        return triggerId;
    }

    public ActorRef getAgentRef() {
        return agentRef;
    }

    public int getTick() {
        return GlobalLibAndConfig.getTick(time);
    }

    @Override
    public int compareTo(TriggerMessage otherTrigger) {
        if (time > otherTrigger.getTime()) {
            return 1;
        } else if (time < otherTrigger.getTime()) {
            return -1;
        } else {
            // higher priority means for a queue, that it comes first
            return otherTrigger.getPriority() - priority;
        }
    }

    public void sendAckMessageListOfTriggersAttached(ActorRef scheduler, ActorRef sender, LinkedList<TriggerMessage> nextTriggerMessages) {
        scheduler.tell(new TriggerAckMessage(getTriggerId(), getTime(), nextTriggerMessages), sender);
    }

    public void sendAckMessageSingleTriggerAttached(ActorRef scheduler, ActorRef sender, TriggerMessage nextTriggerMessage) {
        LinkedList list = null;
        if (nextTriggerMessage != null) {
            list = new LinkedList();
            list.add(nextTriggerMessage);
        }

        sendAckMessageListOfTriggersAttached(scheduler, sender, list);
    }

}
