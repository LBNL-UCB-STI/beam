package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;

public class ToInfrastructureUnPlugMessage {

    private ActorRef charger;

    public ToInfrastructureUnPlugMessage(ActorRef charger) {
        this.setCharger(charger);
    }

    public ActorRef getCharger() {
        return charger;
    }

    private void setCharger(ActorRef charger) {
        this.charger = charger;
    }

}
