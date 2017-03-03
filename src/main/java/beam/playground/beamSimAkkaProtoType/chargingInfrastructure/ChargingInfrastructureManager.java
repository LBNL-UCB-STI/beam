package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.UntypedActor;
import beam.playground.beamSimAkkaProtoType.scheduler.StartSimulationMessage;

public class ChargingInfrastructureManager extends UntypedActor {

	// TODO: performance optimization if this is a bottleneck: instead of one global manager actor, introduce grid of managers
	// -> put set together, evaluate -> reserve best.
	
	@Override
	public void onReceive(Object message) throws Throwable {
		if (message instanceof RequestChargersInRadiusMessage) {
			// TODO: ReplyChargersInRadiusMessage
		} else if (message instanceof ReserveChargerMessage){
			// TODO: ReplyChargersInRadiusMessage
		}
	}
	
}
