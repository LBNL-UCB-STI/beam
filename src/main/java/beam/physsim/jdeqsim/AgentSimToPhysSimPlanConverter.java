package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.pattern.Patterns;
import akka.util.Timeout;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.PathTraversalLib;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.physsim.jdeqsim.akka.AkkaEventHandlerAdapter;
import beam.physsim.jdeqsim.akka.EventManagerActor;
import beam.physsim.jdeqsim.akka.JDEQSimActor;
import beam.router.Modes;
import beam.router.RoutingModel;
import beam.router.r5.NetworkCoordinator;
import beam.router.r5.R5RoutingWorker;
import beam.sim.BeamServices;
import beam.utils.DebugLib;
import com.conveyal.r5.streets.EdgeStore;
import glokka.Registry;
import org.matsim.api.core.v01.Coord;
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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * @Authors asif and rwaraich.
 */
public class AgentSimToPhysSimPlanConverter implements BasicEventHandler {


    private Logger log = LoggerFactory.getLogger(AgentSimToPhysSimPlanConverter.class);
    private Scenario jdeqSimScenario;
    private Population population;
    private PopulationFactory populationFactory;
    private Network network;
    private BeamServices services;

    private ActorRef eventHandlerActorREF;
    private ActorRef jdeqsimActorREF;
    private EventsManager eventsManager;
    private int numberOfLinksRemovedFromRouteAsNonCarModeLinks;
    AgentSimPhysSimInterfaceDebugger agentSimPhysSimInterfaceDebugger;

    public AgentSimToPhysSimPlanConverter(BeamServices services) {
        services.matsimServices().getEvents().addHandler(this);
        this.services = services;
        Scenario agentSimScenario = services.matsimServices().getScenario();
        network = agentSimScenario.getNetwork();

        if (AgentSimPhysSimInterfaceDebugger.DEBUGGER_ON){
            agentSimPhysSimInterfaceDebugger=new AgentSimPhysSimInterfaceDebugger(services);
        }


        resetJDEQSimScenario();
    }

    private void resetJDEQSimScenario() {
        jdeqSimScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        population = jdeqSimScenario.getPopulation();
        populationFactory = jdeqSimScenario.getPopulation().getFactory();
    }


    @Override
    public void reset(int iteration) {

    }

    public void initializeAndRun() {

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
        ActorRef registry = this.services.registry();
        try {

            // TODO: adapt code to send new scenario data to jdeqsim actor each time
            if (eventHandlerActorREF == null) {
                eventHandlerActorREF = registerActor(registry, "EventManagerActor", EventManagerActor.props());
                eventsManager = new AkkaEventHandlerAdapter(eventHandlerActorREF);
            }

            if (jdeqsimActorREF == null) {
                jdeqsimActorREF = registerActor(registry, "JDEQSimActor", JDEQSimActor.props(jdeqSimConfigGroup, jdeqSimScenario, eventsManager, network, this.services.beamRouter()));
            }

            jdeqsimActorREF.tell("start", ActorRef.noSender());
            eventHandlerActorREF.tell("registerJDEQSimREF", jdeqsimActorREF);
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




            PathTraversalEvent ptEvent = (PathTraversalEvent) event;
            String mode = ptEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_MODE);

            if (mode != null && (mode.equalsIgnoreCase("car") || mode.equalsIgnoreCase("bus"))) {

                String links = ptEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LINK_IDS);

                String departureTime = ptEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME);
                String vehicleId = ptEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
                double time = ptEvent.getTime();
                String eventType = ptEvent.getEventType();
                RoutingModel.BeamLeg beamLeg = ptEvent.getBeamLeg();

                beamLeg.duration();
                beamLeg.endTime();
                Modes.BeamMode beamMode = beamLeg.mode();
                RoutingModel.BeamPath beamPath = beamLeg.travelPath();

                Id<Person> personId = Id.createPersonId(vehicleId); //vehiclePersonMap.get(vehicleId1);

                if (personId != null) {
                    boolean personAlreadyExist = false;

                    if (population.getPersons() != null) {
                        personAlreadyExist = population.getPersons().containsKey(personId); // person already exists
                    }


                    List<Id<Link>> linkIds = new ArrayList<>();

                    for (String link : links.split(",")) {
                        Id<Link> linkId = Id.createLinkId(link.trim());
                        linkIds.add(linkId);
                    }


                    // hack: removing non-car links from route
                    // TODO: solve problem properly later
                    List<Id<Link>> removeLinks = new ArrayList<>();
                    for (Id<Link> linkId : linkIds) {
                        if (!network.getLinks().containsKey(linkId)) {
                            removeLinks.add(linkId);
                        }
                    }
                    numberOfLinksRemovedFromRouteAsNonCarModeLinks += removeLinks.size();
                    linkIds.removeAll(removeLinks);

                    if (linkIds.size() == 0) {
                        return;
                    }
                    // end of hack


                    Route route = RouteUtils.createNetworkRoute(linkIds, network);
                    Leg leg = populationFactory.createLeg(beamLeg.mode().matsimMode());
                    leg.setDepartureTime(beamLeg.startTime());
                    leg.setTravelTime(0);
                    leg.setRoute(route);

                    Activity dummyActivity = populationFactory.createActivityFromLinkId("DUMMY", route.getEndLinkId());
                    dummyActivity.setEndTime(beamLeg.startTime());

                    Person person = null;
                    if (personAlreadyExist) {
                        person = population.getPersons().get(personId);

                        Plan plan = person.getSelectedPlan();
                        plan.addActivity(dummyActivity);
                        plan.addLeg(leg);
                    } else {
                        person = populationFactory.createPerson(personId);

                        Plan plan = populationFactory.createPlan();
                        plan.addActivity(dummyActivity);
                        plan.addLeg(leg);

                        plan.setPerson(person);
                        person.addPlan(plan);
                        person.setSelectedPlan(plan);
                        population.addPerson(person);
                    }
                }
            }
        }
    }


    public void startPhysSim() {

        for (Person p : population.getPersons().values()) {
            Plan plan = p.getSelectedPlan();
            Leg leg = (Leg) plan.getPlanElements().get(plan.getPlanElements().size() - 1);

            plan.addActivity(populationFactory.createActivityFromLinkId("DUMMY", leg.getRoute().getEndLinkId()));
        }

        log.warn("numberOfLinksRemovedFromRouteAsNonCarModeLinks (for physsim):" + numberOfLinksRemovedFromRouteAsNonCarModeLinks);
        initializeAndRun();
        resetJDEQSimScenario();
    }
}

