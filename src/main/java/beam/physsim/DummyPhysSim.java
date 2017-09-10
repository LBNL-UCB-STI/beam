package beam.physsim;

import akka.actor.Props;
import akka.actor.UntypedActor;
import beam.sim.BeamServices;

/**
 * BEAM
 */
public class DummyPhysSim extends UntypedActor{
    BeamServices services;

    public DummyPhysSim(BeamServices services) {
        this.services = services;
    }

    public static Props props(BeamServices services){
        return Props.create((Class<?>) DummyPhysSim.class,services);
    }
    @Override
    public void onReceive(Object message) throws Throwable {
        if(message instanceof InitializePhysSim){
            System.out.println("Init Phys Sim");
            getSender().tell(new PhysSimInitialized(),getSelf());
        }
    }
}
