package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;

public class ReplyChargersInRadiusMessage extends TriggerMessage{

	ReplyChargersInRadiusMessage (ActorRef agentRef, double time, int priority){
		super(agentRef,time,priority);
	}
	
}
