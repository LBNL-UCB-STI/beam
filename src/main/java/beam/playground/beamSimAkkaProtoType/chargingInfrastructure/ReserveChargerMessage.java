package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;
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
