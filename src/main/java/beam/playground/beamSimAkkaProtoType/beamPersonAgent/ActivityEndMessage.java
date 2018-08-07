package beam.playground.beamSimAkkaProtoType.beamPersonAgent;

import akka.actor.ActorRef;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;

public class ActivityEndMessage extends TriggerMessage {

    public ActivityEndMessage(ActorRef agentRef, double time, int priority) {
        super(agentRef, time, priority);
    }

}
