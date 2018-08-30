package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;

public class ReserveChargerMessage {

    private ActorRef charger;

    public ReserveChargerMessage(ActorRef charger) {
        this.setCharger(charger);
    }

    public ActorRef getCharger() {
        return charger;
    }

    private void setCharger(ActorRef charger) {
        this.charger = charger;
    }

}
