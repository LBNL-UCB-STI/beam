package beam.playground.beamSimAkkaProtoType.beamPersonAgent;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;
import akka.util.Timeout;
import beam.playground.beamSimAkkaProtoType.GlobalLibAndConfig;
import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.*;
import beam.playground.beamSimAkkaProtoType.scheduler.TriggerMessage;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.LinkedList;
import java.util.Random;

public class BeamPersonAgent extends UntypedActor {

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    private Plan plan;
    private int currentPlanElementIndex = 0;
    private boolean vehicleIsPluggedIn = false;
    private ActorRef chargingInfrastructureManager;
    private DestinationAreaApproachingMessage destinationAreaApproachingMessage;
    private ActorRef reservedCharger;
    private ActorRef scheduler;
    private Random rand;

    // TODO: also send ack messages after receiving messages to scheduler

    public BeamPersonAgent(Plan plan, ActorRef chargingInfrastructureManager, ActorRef scheduler) {
        this.plan = plan;
        this.chargingInfrastructureManager = chargingInfrastructureManager;
        this.scheduler = scheduler;
        this.rand = new Random();
        rand.setSeed(1000);
    }

    private int getRandomNumber(int range) {
        return rand.nextInt(range);
    }

    public void onReceive(TriggerMessage message) {
        if (message instanceof ActivityEndMessage) {
            // TODO: estimate route (introduce delay)

            // TODO: schedule destination area approaching message (based on
            // eucledean distance)
            // log.info(message.toString());

            double arrivalTime = message.getTime() + getRandomNumber(60);

            sendAckMessageSingleTriggerAttached(message, new PersonArrivalAtVehicleMessage(getSelf(), arrivalTime, 0));
            // message.sendAckMessageSingleTriggerAttached(getSender(),
            // getSelf(),
            // new PersonArrivalAtVehicleMessage(getSelf(), arrivalTime, 0));

        } else if (message instanceof PersonArrivalAtVehicleMessage) {
            if (vehicleIsPluggedIn) {
                reservedCharger.tell(new UnplugMessage(), getSelf());
            }

            // getContext().setReceiveTimeout(Duration.apply(100L,
            // TimeUnit.MILLISECONDS));
            try {
                Timeout timeout = new Timeout(Duration.create(GlobalLibAndConfig.latencyRttDelayInMs, "millis"));
                Future<Object> future = Patterns.ask(getSelf(), new IgnoreMessage(), timeout);
                String result = (String) Await.result(future, timeout.duration());
            } catch (Exception e) {

            }

            double arrivalTime = message.getTime() + getRandomNumber(3600);

            // sendAckMessageSingleTriggerAttached(message,null);
            sendAckMessageSingleTriggerAttached(message,
                    new DestinationAreaApproachingMessage(getSelf(), arrivalTime, 0));

        } else if (message instanceof DestinationAreaApproachingMessage) {
            this.destinationAreaApproachingMessage = (DestinationAreaApproachingMessage) message;
            chargingInfrastructureManager.tell(new RequestChargersInRadiusMessage(), getSelf());

        } else if (message instanceof ChargerArrivalMessage) {

            reservedCharger.tell(new PluginMessage(getSelf()), getSelf());

            double arrivalTime = message.getTime() + getRandomNumber(2 * 60);

            // sendAckMessageSingleTriggerAttached(message,null);
            sendAckMessageSingleTriggerAttached(message,
                    new ActStartMessage(getSelf(), arrivalTime, 0));
        } else if (message instanceof ActStartMessage) {

            currentPlanElementIndex += 2;

            if (plan.getPlanElements().size() > currentPlanElementIndex + 1) {
                double actEndTime = ((Activity) plan.getPlanElements().get(currentPlanElementIndex)).getEndTime();

                double arrivalTime;

                if (actEndTime < message.getTime()) {
                    arrivalTime = message.getTime() + getRandomNumber(2 * 60);
                } else {
                    arrivalTime = actEndTime;
                }


                sendAckMessageSingleTriggerAttached(message,
                        new ActivityEndMessage(getSelf(), arrivalTime, 0));
            } else {
                sendAckMessageSingleTriggerAttached(message,
                        null);
            }
        }
    }

    @Override
    public void onReceive(Object message) throws Throwable {
        GlobalLibAndConfig.printMessage(log, message);
        modelAgentCompuationBurden();
        if (message instanceof TriggerMessage) {
            onReceive((TriggerMessage) message);
        } else if (message instanceof ReplyChargersInRadiusMessage) {
            ReplyChargersInRadiusMessage replyChargersInRadiusMessage = (ReplyChargersInRadiusMessage) message;
            chargingInfrastructureManager.tell(new ReserveChargerMessage(replyChargersInRadiusMessage.getCharger()), getSelf());

        } else if (message instanceof ChargerReservationConfirmationMessage) {
            ChargerReservationConfirmationMessage chargerReservationConfirmationMessage = (ChargerReservationConfirmationMessage) message;
            this.reservedCharger = chargerReservationConfirmationMessage.getCharger();

            // route to charger (introduce delay)
            try {
                Timeout timeout = new Timeout(Duration.create(GlobalLibAndConfig.latencyRttDelayInMs, "millis"));
                Future<Object> future = Patterns.ask(getSelf(), new IgnoreMessage(), timeout);
                String result = (String) Await.result(future, timeout.duration());
            } catch (Exception e) {

            }

            double arrivalTime = this.destinationAreaApproachingMessage.getTime() + getRandomNumber(3600);

            sendAckMessageSingleTriggerAttached(this.destinationAreaApproachingMessage,
                    new ChargerArrivalMessage(getSelf(), arrivalTime, 0));
        } else if (message instanceof UnplugAckMessage) {
            this.reservedCharger = null;
        } else if (message instanceof IgnoreMessage) {
            // System.out.println();
        } else {
            DebugLib.stopSystemAndReportInconsistency("unexpected message type received:" + message);
        }

    }

    private void sendAckMessageSingleTriggerAttached(TriggerMessage oldTriggerMessage,
                                                     TriggerMessage newTriggerMessage) {
        oldTriggerMessage.sendAckMessageSingleTriggerAttached(scheduler, getSelf(), newTriggerMessage);
    }

    private void modelAgentCompuationBurden() {
        LinkedList<Long> queue = new LinkedList<>();
        for (long i = 0; i < GlobalLibAndConfig.agentComputationBurden; i++) {
            queue.add(i);
            queue.removeFirst();
        }
    }

}
