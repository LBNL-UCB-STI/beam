package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;

public class PluginMessage {


    private ActorRef beamPersonAgentRef;

    public PluginMessage(ActorRef beamPersonAgentRef) {
        this.beamPersonAgentRef = beamPersonAgentRef;
    }

    public ActorRef getBeamPersonAgentRef() {
        return beamPersonAgentRef;
    }


}
