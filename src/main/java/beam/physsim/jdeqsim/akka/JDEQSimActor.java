package beam.physsim.jdeqsim.akka;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import beam.router.BeamRouter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;


public class JDEQSimActor extends UntypedActor {

    private JDEQSimulation jdeqSimulation;
    private EventsManager events;
    private Network network;
    private TravelTimeCalculator travelTimeCalculator;
    ActorRef beamRouterRef;

    public JDEQSimActor(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, final Network network, final ActorRef beamRouterRef) {
        this.events = events;
        this.network = network;
        this.beamRouterRef = beamRouterRef;

        TravelTimeCalculatorConfigGroup ttccg = new TravelTimeCalculatorConfigGroup();
        travelTimeCalculator = new TravelTimeCalculator(network, ttccg);
        events.addHandler(travelTimeCalculator);

        jdeqSimulation = new JDEQSimulation(config, scenario, events, network);
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof String) {
            String s = (String) msg;
            if (s.equalsIgnoreCase("start")) {
                jdeqSimulation.run();
                events.finishProcessing();
            } else if (s.equalsIgnoreCase("eventsProcessingFinished")) {
                beamRouterRef.tell(new BeamRouter.UpdateTravelTime(travelTimeCalculator), getSelf());
            }
        }
    }


    public static Props props(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, final Network network, final ActorRef beamRouterRef) {
        return Props.create(JDEQSimActor.class, config, scenario, events, network, beamRouterRef);
    }

}
