package beam.playground.beamSimAkkaProtoType.chargingInfrastructure;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import beam.playground.beamSimAkkaProtoType.GlobalLibAndConfig;

public class Charger extends UntypedActor {

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private ActorRef chargingInfrastructureManager;

    private ActorRef beamPersonAgentRef;

    public Charger(ActorRef chargingInfrastructureManager) {
        this.chargingInfrastructureManager = chargingInfrastructureManager;
    }

    @Override
    public void onReceive(Object message) throws Throwable {
        GlobalLibAndConfig.printMessage(log, message);
        if (message instanceof PluginMessage) {
            PluginMessage pluginMessage = (PluginMessage) message;
            beamPersonAgentRef = ((PluginMessage) message).getBeamPersonAgentRef();
        } else if (message instanceof UnplugMessage) {
            ReserveChargerMessage reserveChargerMessage = (ReserveChargerMessage) message;
            chargingInfrastructureManager.tell(new ToInfrastructureUnPlugMessage(getSelf()), getSelf());
        } else if (message instanceof ToInfrastructureUnPlugAckMessage) {
            beamPersonAgentRef.tell(new UnplugAckMessage(), getSelf());
        }


        // plugin and plug out messages,

        // plugout: synchronized.

    }

}
