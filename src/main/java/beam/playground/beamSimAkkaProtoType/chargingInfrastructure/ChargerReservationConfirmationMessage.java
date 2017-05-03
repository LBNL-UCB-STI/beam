package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;

public class ChargerReservationConfirmationMessage {

	private ActorRef charger;

	public ChargerReservationConfirmationMessage(ActorRef charger) {
		this.setCharger(charger);
	}

	public ActorRef getCharger() {
		return charger;
	}

	private void setCharger(ActorRef charger) {
		this.charger = charger;
	}
	
}
