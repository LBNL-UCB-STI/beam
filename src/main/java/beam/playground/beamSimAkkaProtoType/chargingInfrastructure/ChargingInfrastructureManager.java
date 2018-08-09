package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import beam.playground.beamSimAkkaProtoType.GlobalLibAndConfig;
import beam.utils.DebugLib;

import java.util.HashSet;
import java.util.LinkedList;

public class ChargingInfrastructureManager extends UntypedActor {

    // TODO: performance optimization if this is a bottleneck: instead of one global manager actor, introduce grid of managers
    // -> put set together, evaluate -> reserve best.

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    LinkedList<ActorRef> availableChargers = new LinkedList();
    HashSet<ActorRef> reservedChargers = new HashSet();


    public ChargingInfrastructureManager(Integer numberOfChargers) {
        for (int i = 0; i < numberOfChargers; i++) {
            ActorRef charger = getContext().actorOf(Props.create(Charger.class, getSelf()), "charger-" + i);
            availableChargers.add(charger);
        }
    }

    // NOTE: no detailed modelling of reservation, etc. needed/done, as this is not the point of this prototype

    @Override
    public void onReceive(Object message) throws Throwable {
        GlobalLibAndConfig.printMessage(log, message);
        if (message instanceof RequestChargersInRadiusMessage) {
            ActorRef charger = availableChargers.getFirst();
            sender().tell(new ReplyChargersInRadiusMessage(charger), getSelf());
        } else if (message instanceof ReserveChargerMessage) {
            ReserveChargerMessage reserveChargerMessage = (ReserveChargerMessage) message;
            sender().tell(new ChargerReservationConfirmationMessage(reserveChargerMessage.getCharger()), getSelf());
        } else if (message instanceof ToInfrastructureUnPlugMessage) {
            ToInfrastructureUnPlugMessage toInfrastructureUnPlugMessage = (ToInfrastructureUnPlugMessage) message;
            reservedChargers.remove(toInfrastructureUnPlugMessage.getCharger());
            availableChargers.addLast(toInfrastructureUnPlugMessage.getCharger());
            getSender().tell(new ToInfrastructureUnPlugAckMessage(), getSelf());
        } else {
            log.info(getSender().toString());
            DebugLib.stopSystemAndReportInconsistency("unexpected message type received:" + message);
        }
    }

}
