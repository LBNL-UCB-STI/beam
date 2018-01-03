package beam.physsim.jdeqsim.akka;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import beam.router.BeamRouter;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import org.matsim.core.utils.collections.Tuple;


public class JDEQSimActor extends UntypedActor {

    public static final String START_PHYSSIM = "startPhyssim";
    private JDEQSimulation jdeqSimulation;
    private JDEQSimConfigGroup config;
    private Scenario agentSimScenario;
    private EventsManager events;

    private ActorRef beamRouterRef;
    private Population jdeqSimPopulation;

    public JDEQSimActor(final JDEQSimConfigGroup config, final Scenario agentSimScenario, final EventsManager events, final ActorRef beamRouterRef) {
        this.config = config;
        this.agentSimScenario = agentSimScenario;
        this.events = events;
        this.beamRouterRef = beamRouterRef;
    }

    // TODO: reset handler properly after each iteration
    private void runPhysicalSimulation(){


        jdeqSimulation = new JDEQSimulation(config, jdeqSimPopulation , events, agentSimScenario.getNetwork(), agentSimScenario.getConfig().plans().getActivityDurationInterpretation());
        jdeqSimulation.run();
        events.finishProcessing();
    }


    @Override
    public void onReceive(Object message) throws Exception {

        if (message instanceof Tuple) {
            Tuple tuple = (Tuple) message;
            String messageString = (String) tuple.getFirst();

            if (messageString.equalsIgnoreCase(START_PHYSSIM)){
                this.jdeqSimPopulation = (Population) tuple.getSecond();
                runPhysicalSimulation();
            } else {
                DebugLib.stopSystemAndReportUnknownMessageType();
            }

        } else if (message instanceof TravelTimeCalculator){
            TravelTimeCalculator travelTimeCalculator = (TravelTimeCalculator) message;
            beamRouterRef.tell(new BeamRouter.UpdateTravelTime(travelTimeCalculator.getLinkTravelTimes()), getSelf());
        }else {
            DebugLib.stopSystemAndReportUnknownMessageType();
        }
    }


    public static Props props(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, final ActorRef beamRouterRef) {
        return Props.create(JDEQSimActor.class, config, scenario, events, beamRouterRef);
    }

}
