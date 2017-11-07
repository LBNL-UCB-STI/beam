package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.pattern.Patterns;
import akka.util.Timeout;
import beam.agentsim.events.PathTraversalEvent;
import beam.physsim.jdeqsim.akka.AkkaEventHandlerAdapter;
import beam.physsim.jdeqsim.akka.EventManagerActor;
import beam.physsim.jdeqsim.akka.JDEQSimActor;
import beam.sim.BeamServices;
import glokka.Registry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Await;
import scala.util.Left;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * @Authors asif and rwaraich.
 */
public class AgentSimToPhysSimPlanConverter implements BasicEventHandler {


    public static final String CAR = "car";
    public static final String BUS = "bus";
    public static final String DUMMY_ACTIVITY = "DummyActivity";
    private Logger log = LoggerFactory.getLogger(AgentSimToPhysSimPlanConverter.class);
    private Scenario jdeqSimScenario;
    private PopulationFactory populationFactory;
    private Scenario agentSimScenario;
    private BeamServices services;

    private ActorRef eventHandlerActorREF;
    private ActorRef jdeqsimActorREF;
    private EventsManager eventsManager;
    private int numberOfLinksRemovedFromRouteAsNonCarModeLinks;
    AgentSimPhysSimInterfaceDebugger agentSimPhysSimInterfaceDebugger;

    public AgentSimToPhysSimPlanConverter(BeamServices services) {
        services.matsimServices().getEvents().addHandler(this);
        this.services = services;
        agentSimScenario = services.matsimServices().getScenario();

        if (AgentSimPhysSimInterfaceDebugger.DEBUGGER_ON){
            log.warn("AgentSimPhysSimInterfaceDebugger is enabled");
            agentSimPhysSimInterfaceDebugger=new AgentSimPhysSimInterfaceDebugger(services);
        }

        preparePhysSimForNewIteration();
    }

    private void preparePhysSimForNewIteration() {
        jdeqSimScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        populationFactory = jdeqSimScenario.getPopulation().getFactory();
    }


    @Override
    public void reset(int iteration) {

    }

    public void initializeActorsAndRunPhysSim() {

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
        ActorRef registry = this.services.registry();
        try {

            // TODO: adapt code to send new scenario data to jdeqsim actor each time
            if (eventHandlerActorREF == null) {
                eventHandlerActorREF = registerActor(registry, "EventManagerActor", EventManagerActor.props(agentSimScenario.getNetwork()));
                eventsManager = new AkkaEventHandlerAdapter(eventHandlerActorREF);
            }

            if (jdeqsimActorREF == null) {
                jdeqsimActorREF = registerActor(registry, "JDEQSimActor", JDEQSimActor.props(jdeqSimConfigGroup,agentSimScenario, eventsManager,   this.services.beamRouter()));
            }

            jdeqsimActorREF.tell(new Tuple<String,Population>(JDEQSimActor.START_PHYSSIM,jdeqSimScenario.getPopulation()), ActorRef.noSender());
            eventHandlerActorREF.tell(EventManagerActor.REGISTER_JDEQSIM_REF, jdeqsimActorREF);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ActorRef registerActor(ActorRef registry, String actorName, Props props) throws Exception {
        glokka.Registry.Register r = new glokka.Registry.Register(actorName, new Left(props));
        Timeout timeout = new Timeout(10, TimeUnit.SECONDS);
        scala.concurrent.Future<Object> future = Patterns.ask(registry, r, timeout);
        Registry.Created eventManagerActorCreated = (Registry.Created) Await.result(future, timeout.duration());
        return eventManagerActorCreated.ref();
    }

    @Override
    public void handleEvent(Event event) {

        if (AgentSimPhysSimInterfaceDebugger.DEBUGGER_ON){
            agentSimPhysSimInterfaceDebugger.handleEvent(event);
        }

        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pathTraversalEvent = (PathTraversalEvent) event;
            String mode = pathTraversalEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_MODE);

            if (mode != null && (mode.equalsIgnoreCase(CAR) || mode.equalsIgnoreCase(BUS))) {

                String links = pathTraversalEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LINK_IDS);
                double departureTime = Double.parseDouble(pathTraversalEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME));
                String vehicleId = pathTraversalEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);

                Id<Person> personId = Id.createPersonId(vehicleId);
                initializePersonAndPlanIfNeeded(personId);

                // add previous activity and leg to plan
                Person person=jdeqSimScenario.getPopulation().getPersons().get(personId);
                Plan plan=person.getSelectedPlan();
                Leg leg=createLeg(mode, links, departureTime);

                if (leg==null){
                    return; // dont't process leg further, if empty
                }

                Activity previousActivity = populationFactory.createActivityFromLinkId(DUMMY_ACTIVITY, leg.getRoute().getStartLinkId());
                previousActivity.setEndTime(departureTime);
                plan.addActivity(previousActivity);
                plan.addLeg(leg);
            }
        }
    }

    private void initializePersonAndPlanIfNeeded(Id<Person> personId) {
        if (!jdeqSimScenario.getPopulation().getPersons().containsKey(personId)){
            Person person = populationFactory.createPerson(personId);
            Plan plan = populationFactory.createPlan();
            plan.setPerson(person);
            person.addPlan(plan);
            person.setSelectedPlan(plan);
            jdeqSimScenario.getPopulation().addPerson(person);
        }
    }

    private Leg createLeg(String mode, String links, double departureTime) {
        List<Id<Link>> linkIds = new ArrayList<>();

        for (String link : links.split(",")) {
            Id<Link> linkId = Id.createLinkId(link.trim());
            linkIds.add(linkId);
        }


        // hack: removing non-road links from route
        // TODO: debug problem properly, so that no that no events for physsim contain non-road links
        List<Id<Link>> removeLinks = new ArrayList<>();
        for (Id<Link> linkId : linkIds) {
            if (!agentSimScenario.getNetwork().getLinks().containsKey(linkId)) {
                removeLinks.add(linkId);
            }
        }
        numberOfLinksRemovedFromRouteAsNonCarModeLinks += removeLinks.size();
        linkIds.removeAll(removeLinks);

        if (linkIds.size() == 0) {
            return null;
        }
        // end of hack


        Route route = RouteUtils.createNetworkRoute(linkIds, agentSimScenario.getNetwork());
        Leg leg = populationFactory.createLeg(mode);
        leg.setDepartureTime(departureTime);
        leg.setTravelTime(0);
        leg.setRoute(route);
        return leg;
    }



    public void startPhysSim() {
        createLastActivityOfDayForPopulation();

        log.warn("numberOfLinksRemovedFromRouteAsNonCarModeLinks (for physsim):" + numberOfLinksRemovedFromRouteAsNonCarModeLinks);
        initializeActorsAndRunPhysSim();

        preparePhysSimForNewIteration();
    }

    private void createLastActivityOfDayForPopulation() {
        for (Person p : jdeqSimScenario.getPopulation().getPersons().values()) {
            Plan plan = p.getSelectedPlan();
            Leg leg = (Leg) plan.getPlanElements().get(plan.getPlanElements().size() - 1);
            plan.addActivity(populationFactory.createActivityFromLinkId(DUMMY_ACTIVITY, leg.getRoute().getEndLinkId()));
        }
    }
}

