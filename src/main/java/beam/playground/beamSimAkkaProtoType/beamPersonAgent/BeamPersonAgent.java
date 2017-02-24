package beam.playground.beamSimAkkaProtoType.beamPersonAgent;

import java.util.concurrent.TimeUnit;

import org.matsim.api.core.v01.population.Plan;

import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;
import akka.util.Timeout;
import beam.parking.lib.DebugLib;
import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.ReplyChargersInRadiusMessage;
import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.ReservationConfirmationMessage;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;

public class BeamPersonAgent extends UntypedActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	private Plan plan;
	private int currentPlanElementIndex = 0;
	private boolean vehicleIsPluggedIn = false;

	// TODO: also send ack messages after receiving messages to scheduler

	public BeamPersonAgent(Plan plan) {
		this.plan = plan;

	}

	public void onReceive(TriggerMessage message) {
		if (message instanceof ActivityEndMessage) {
			// TODO: estimate route (introduce delay)

			// TODO: schedule destination area approaching message (based on
			// eucledean distance)
			// log.info(message.toString());

			double arrivalTime = message.getTime() + 60;

			
			sendAckMessageSingleTriggerAttached(message,new PersonArrivalAtVehicleMessage(getSelf(), arrivalTime, 0));
		//	message.sendAckMessageSingleTriggerAttached(getSender(), getSelf(),
		//			new PersonArrivalAtVehicleMessage(getSelf(), arrivalTime, 0));

		} else if (message instanceof PersonArrivalAtVehicleMessage) {
			if (vehicleIsPluggedIn) {
				// TODO: unplug vehicle
			}

			// getContext().setReceiveTimeout(Duration.apply(100L,
			// TimeUnit.MILLISECONDS));
			try {
				Timeout timeout = new Timeout(Duration.create(1, "seconds"));
				Future<Object> future = Patterns.ask(getSelf(), new IgnoreMessage(), timeout);
				String result = (String) Await.result(future, timeout.duration());
			} catch (Exception e) {

			}

			// TODO: add randomness
			double arrivalTime = 3600;

			sendAckMessageSingleTriggerAttached(message,new DestinationAreaApproachingMessage(getSelf(), arrivalTime, 0));
			
		} else if (message instanceof DestinationAreaApproachingMessage) {
			// TODO: find chargers

			// TODO: route to charger (introduce delay)

			// TODO: schedule message: charger arrival message

		} else if (message instanceof ChargerArrivalMessage) {
			// TODO: plugin message to reserved plug

			// TODO: schedule plugout within 30min -> message send to

			// TODO: schedule activity start message
		} else if (message instanceof ActStartMessage) {

			currentPlanElementIndex += 2;

			// TODO: schedule activity end message (only for last one, don't do
			// it)
		} else if (message instanceof ReplyChargersInRadiusMessage) {
			// TODO: send ReserveChargerMessage to charging infrastructure
			// manager
		} else if (message instanceof ReservationConfirmationMessage) {
			// TODO: send plugin charger message to charger which was confirmed

			// TODO: schedule trigger plugout for later
		}
	}

	@Override
	public void onReceive(Object message) throws Throwable {
		if (message instanceof TriggerMessage) {
			onReceive((TriggerMessage) message);
		} else if (message instanceof IgnoreMessage) {
			System.out.println();
		} else {
			DebugLib.stopSystemAndReportInconsistency("unexpected message type received:" + message);
		}

	}
	
	private void sendAckMessageSingleTriggerAttached(TriggerMessage oldTriggerMessage,TriggerMessage newTriggerMessage){
		oldTriggerMessage.sendAckMessageSingleTriggerAttached(getSender(), getSelf(),
				newTriggerMessage);
	}

}
