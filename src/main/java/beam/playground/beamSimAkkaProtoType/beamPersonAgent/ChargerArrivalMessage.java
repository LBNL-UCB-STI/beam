package beam.playground.beamSimAkkaProtoType.beamPersonAgent;

import akka.actor.ActorRef;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;

public class ChargerArrivalMessage extends TriggerMessage {

    ChargerArrivalMessage(ActorRef agentRef, double time, int priority) {
        super(agentRef, time, priority);
    }

}
