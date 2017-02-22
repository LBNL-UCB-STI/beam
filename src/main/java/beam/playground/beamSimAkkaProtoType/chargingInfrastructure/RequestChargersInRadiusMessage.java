package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;

public class RequestChargersInRadiusMessage extends TriggerMessage{

	RequestChargersInRadiusMessage (ActorRef agentRef, double time, int priority){
		super(agentRef,time,priority);
	}
	
}
