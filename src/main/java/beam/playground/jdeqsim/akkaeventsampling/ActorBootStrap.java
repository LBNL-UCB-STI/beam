package beam.playground.jdeqsim.akkaeventsampling;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import beam.playground.jdeqsim.akkaeventsampling.messages.SchedulerActorJobMessage;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimulation;
import org.matsim.core.scenario.ScenarioUtils;


public class ActorBootStrap {
    public static ActorSystem system;

    public static void main(String[] args) {
        String defaultFileName = "C:/Users/salma_000/Desktop/MatSim/matsim-master/examples/scenarios/equil/config.xml";
        Config config = ConfigUtils.loadConfig(defaultFileName);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        system = ActorSystem.create("EventSamplingActorSystem");
        ActorRef router = system.actorOf(Props.create(EventRouter.class), EventRouter.ACTOR_NAME);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }
        ActorRef scheduleActorUtilRef = system.actorOf(Props.create(SchedulerActorUtil.class), SchedulerActorUtil.ACTOR_NAME);
        scheduleActorUtilRef.tell(new SchedulerActorJobMessage(500), ActorRef.noSender());

        CustomEventManager customEventManager = new CustomEventManager(router);
        EventsManager eventsManager = customEventManager;


        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
        JDEQSimulation jdeqSimulation = new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);

        jdeqSimulation.run();

        eventsManager.finishProcessing();


    }
}
