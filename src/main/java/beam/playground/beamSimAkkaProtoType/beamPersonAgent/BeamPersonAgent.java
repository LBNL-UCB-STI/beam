package beam.playground.beamSimAkkaProtoType.beamPersonAgent;

import akka.actor.UntypedActor;
import beam.parking.lib.DebugLib;
import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.ReplyChargersInRadiusMessage;
import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.ReservationConfirmationMessage;

public class BeamPersonAgent extends UntypedActor {


	
	// TODO: also send ack messages after receiving messages to scheduler
	
	@Override
	public void onReceive(Object message) throws Throwable {
		if (message instanceof ActivityEndMessage){
			// TODO: estimate route (introduce delay)
			
			// TODO: schedule destination area approaching message (based on eucledean distance)
			

		} else if (message instanceof DestinationAreaApproachingMessage){
			// TODO: find chargers
			
			// TODO: route to charger (introduce delay)
			
			// TODO: schedule message: charger arrival message
			
		} else if (message instanceof ChargerArrivalMessage){
			// TODO: plugin message to reserved plug
			
			// TODO: schedule plugout within 30min -> message send to 
			
			// TODO: schedule activity start message
		}else if (message instanceof ActStartMessage){
			// TODO: schedule activity end message (only for last one, don't do it)
		} else if (message instanceof ReplyChargersInRadiusMessage){
			// TODO: send ReserveChargerMessage to charging infrastructure manager
		}else if (message instanceof ReservationConfirmationMessage){
			// TODO: send plugin charger message to charger which was confirmed
			
			// TODO: schedule trigger plugout for later
		}else  {
			DebugLib.stopSystemAndReportInconsistency("unexpected message type received:" + message);
		}
	
		
		
	}

}
