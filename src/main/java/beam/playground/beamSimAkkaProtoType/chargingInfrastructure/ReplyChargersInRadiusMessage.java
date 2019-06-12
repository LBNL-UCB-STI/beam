package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;

public class ReplyChargersInRadiusMessage {

    private ActorRef charger;

    public ReplyChargersInRadiusMessage(ActorRef charger) {
        this.setCharger(charger);
    }

    public ActorRef getCharger() {
        return charger;
    }

    private void setCharger(ActorRef charger) {
        this.charger = charger;
    }


}
