package beam.playground.jdeqsim.akka;

import akka.actor.UntypedActor;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimulation;

public class JDEQSimActor extends UntypedActor {

    private JDEQSimulation jdeqSimulation;
    private EventsManager events;

    public JDEQSimActor(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events) {
        this.events = events;
        jdeqSimulation = new JDEQSimulation(config, scenario, events);
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof String) {
            String s = (String) msg;
            if (s.equalsIgnoreCase("start")) {
                jdeqSimulation.run();
                events.finishProcessing();
            }
        }
    }

}
