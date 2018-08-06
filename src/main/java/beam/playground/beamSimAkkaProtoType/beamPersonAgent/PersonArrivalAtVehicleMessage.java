package beam.playground.beamSimAkkaProtoType.beamPersonAgent;

import akka.actor.ActorRef;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;

public class PersonArrivalAtVehicleMessage extends TriggerMessage {

    PersonArrivalAtVehicleMessage(ActorRef agentRef, double time, int priority) {
        super(agentRef, time, priority);
    }

}
