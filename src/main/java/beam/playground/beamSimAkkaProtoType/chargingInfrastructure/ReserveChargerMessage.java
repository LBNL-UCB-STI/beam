package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;
import akka.actor.ActorRef;

public class ReserveChargerMessage extends TriggerMessage{

	public ReserveChargerMessage (ActorRef agentRef, double time, int priority){
		super(agentRef,time,priority);
	}
	
}
