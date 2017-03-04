package beam.playground.beamSimAkkaProtoType.beamPersonAgent;

import java.util.concurrent.TimeUnit;

import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.*;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.population.Plan;

import akka.actor.ActorRef;
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;
import akka.util.Timeout;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import beam.playground.beamSimAkkaProtoType.scheduler.StartSimulationMessage;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;

public class BeamPersonAgent extends UntypedActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	private Plan plan;
	private int currentPlanElementIndex = 0;
	private boolean vehicleIsPluggedIn = false;
	private ActorRef chargingInfrastructureManager;
	private TriggerMessage destinationAreaApproachingMessage;
	private ActorRef reservedPlug;

	// TODO: also send ack messages after receiving messages to scheduler

	public BeamPersonAgent(Plan plan, ActorRef chargingInfrastructureManager) {
		this.plan = plan;
		this.chargingInfrastructureManager = chargingInfrastructureManager;
	}

	public void onReceive(TriggerMessage message) {
		if (message instanceof ActivityEndMessage) {
			// TODO: estimate route (introduce delay)

			// TODO: schedule destination area approaching message (based on
			// eucledean distance)
			// log.info(message.toString());

			double arrivalTime = message.getTime() + 60;

			sendAckMessageSingleTriggerAttached(message, new PersonArrivalAtVehicleMessage(getSelf(), arrivalTime, 0));
			// message.sendAckMessageSingleTriggerAttached(getSender(),
			// getSelf(),
			// new PersonArrivalAtVehicleMessage(getSelf(), arrivalTime, 0));

		} else if (message instanceof PersonArrivalAtVehicleMessage) {
			if (vehicleIsPluggedIn) {
				reservedPlug.tell(new UnplugMessage(), getSelf());
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
			double arrivalTime = message.getTime() + 3600;

			// sendAckMessageSingleTriggerAttached(message,null);
			sendAckMessageSingleTriggerAttached(message,
					new DestinationAreaApproachingMessage(getSelf(), arrivalTime, 0));

		} else if (message instanceof DestinationAreaApproachingMessage) {
			this.destinationAreaApproachingMessage = (TriggerMessage)message;
			chargingInfrastructureManager.tell(new RequestChargersInRadiusMessage(), getSelf());

		} else if (message instanceof ChargerArrivalMessage) {
			
			reservedPlug.tell(new PluginMessage(), getSelf());
			
			double arrivalTime = message.getTime() + 2*60;

			// sendAckMessageSingleTriggerAttached(message,null);
			sendAckMessageSingleTriggerAttached(message,
					new ActStartMessage(getSelf(), arrivalTime, 0));
		} else if (message instanceof ActStartMessage) {

			currentPlanElementIndex += 2;

			//TODO: take activity end message time from plan
			double arrivalTime = message.getTime() + 2*60;
			
			// TODO: schedule activity end message (only for last one, don't do
						// it)
			sendAckMessageSingleTriggerAttached(message,
					new ActivityEndMessage(getSelf(), arrivalTime, 0));
		}
	}

	@Override
	public void onReceive(Object message) throws Throwable {
		if (message instanceof TriggerMessage) {
			onReceive((TriggerMessage) message);
		} else if (message instanceof ReplyChargersInRadiusMessage) {
			chargingInfrastructureManager.tell(new ReserveChargerMessage(), getSelf());

		} else if (message instanceof ReservationConfirmationMessage) {

			// route to charger (introduce delay)
			try {
				Timeout timeout = new Timeout(Duration.create(1, "seconds"));
				Future<Object> future = Patterns.ask(getSelf(), new IgnoreMessage(), timeout);
				String result = (String) Await.result(future, timeout.duration());
			} catch (Exception e) {

			}

			// TODO: add randomness
			double arrivalTime = this.destinationAreaApproachingMessage.getTime() + 3600;

			sendAckMessageSingleTriggerAttached(this.destinationAreaApproachingMessage,
					new ChargerArrivalMessage(getSelf(), arrivalTime, 0));
		} else if (message instanceof IgnoreMessage) {
			// System.out.println();
		} else {
			DebugLib.stopSystemAndReportInconsistency("unexpected message type received:" + message);
		}

	}

	private void sendAckMessageSingleTriggerAttached(TriggerMessage oldTriggerMessage,
			TriggerMessage newTriggerMessage) {
		oldTriggerMessage.sendAckMessageSingleTriggerAttached(getSender(), getSelf(), newTriggerMessage);
	}

}
