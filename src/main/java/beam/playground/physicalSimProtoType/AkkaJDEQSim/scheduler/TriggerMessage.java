package beam.playground.physicalSimProtoType.AkkaJDEQSim.scheduler;

import java.util.LinkedList;
import java.util.List;

import akka.actor.ActorRef;
import beam.playground.beamSimAkkaProtoType.GlobalLibAndConfig;

public class TriggerMessage implements Comparable<TriggerMessage> {

	//TODO: triggerId can be removed (not needed for algorithm implementation), but might be useful for debugging/testing
	private int triggerId;

	private static int triggerIdCounter=0;
	protected int priority;
	private ActorRef receivingActorRef;
	private double messageArrivalTime = 0;
	
	public double getMessageArrivalTime() {
		return messageArrivalTime;
	}

	public void setMessageArrivalTime(double messageArrivalTime) {
		this.messageArrivalTime = messageArrivalTime;
	}
	
	public int getPriority(){
		return priority;
	}
	
	public TriggerMessage (){
		this.triggerId=triggerIdCounter++;
	}
	
	public TriggerMessage (ActorRef receivingActorRef, double time, int priority){
		this.setReceivingActorRef(receivingActorRef);
		this.setMessageArrivalTime(time);
		this.priority = priority;
		this.triggerId=triggerIdCounter++;
	}
	
	public int getTriggerId() {
		return triggerId;
	}

	public int getTick(){
		return GlobalLibAndConfig.getTick(getMessageArrivalTime());
	}

	@Override
	public int compareTo(TriggerMessage otherTrigger) {
		if (messageArrivalTime > otherTrigger.getMessageArrivalTime()) {
			return 1;
		} else if (messageArrivalTime < otherTrigger.getMessageArrivalTime()) {
			return -1;
		} else {
			// higher priority means for a queue, that it comes first
			return otherTrigger.getPriority() - priority;
		}
	}
	
	public void sendAckMessageListOfTriggersAttached(ActorRef scheduler, ActorRef sender, LinkedList<TriggerMessage> nextTriggerMessages){
		scheduler.tell(new TriggerAckMessage(getTriggerId(),getMessageArrivalTime(),nextTriggerMessages),sender);
	}
	
	public void sendAckMessageSingleTriggerAttached(ActorRef scheduler, ActorRef sender, TriggerMessage nextTriggerMessage){
		LinkedList list=null;
		if (nextTriggerMessage!=null){
			list=new LinkedList();
			list.add(nextTriggerMessage);
		}
		
		sendAckMessageListOfTriggersAttached(scheduler,sender, list);
	}

	public ActorRef getReceivingActorRef() {
		return receivingActorRef;
	}

	public void setReceivingActorRef(ActorRef receivingActorRef) {
		this.receivingActorRef = receivingActorRef;
	}

}
