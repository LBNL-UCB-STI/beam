package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.pattern.Patterns;
import akka.util.Timeout;
import beam.agentsim.events.PathTraversalEvent;
import beam.physsim.jdeqsim.akka.AkkaEventHandlerAdapter;
import beam.physsim.jdeqsim.akka.EventManagerActor;
import beam.physsim.jdeqsim.akka.JDEQSimActor;
import beam.router.Modes;
import beam.router.RoutingModel;
import beam.router.r5.NetworkCoordinator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Await;
import scala.util.Left;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * Created by asif on 8/18/2017.
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

    public AgentSimToPhysSimPlanConverter(BeamServices services){

        this.services = services;
        Scenario agentSimScenario = this.services.matsimServices().getScenario();
        network = agentSimScenario.getNetwork();

        resetJDEQSimScenario();
    }

    private void resetJDEQSimScenario(){
        jdeqSimScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        population = jdeqSimScenario.getPopulation();
        populationFactory = jdeqSimScenario.getPopulation().getFactory();
    }


    @Override
    public void reset(int iteration) {

        for(Person p : population.getPersons().values()){
            Plan plan = p.getSelectedPlan();
            Leg leg = (Leg)plan.getPlanElements().get(plan.getPlanElements().size() - 1);

            plan.addActivity(populationFactory.createActivityFromLinkId("DUMMY", leg.getRoute().getEndLinkId()));
        }
        initializeAndRun();
        resetJDEQSimScenario();
    }

    public void initializeAndRun(){

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
        ActorRef registry = this.services.registry();
        try{

            // TODO: adapt code to send new scenario data to jdeqsim actor each time
            if(eventHandlerActorREF == null) {
                eventHandlerActorREF = registerActor(registry, "EventManagerActor", EventManagerActor.props());
                eventsManager = new AkkaEventHandlerAdapter(eventHandlerActorREF);
            }

            if(jdeqsimActorREF == null) {
                jdeqsimActorREF = registerActor(registry, "JDEQSimActor", JDEQSimActor.props(jdeqSimConfigGroup, jdeqSimScenario, eventsManager, network, this.services.beamRouter()));
            }

            jdeqsimActorREF.tell("start", ActorRef.noSender());
            eventHandlerActorREF.tell("registerJDEQSimREF", jdeqsimActorREF);
        }
        catch(Exception e){
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

        if(event instanceof PathTraversalEvent){
            PathTraversalEvent ptEvent = (PathTraversalEvent)event;
            String mode = ptEvent.getAttributes().get(ptEvent.ATTRIBUTE_MODE());

            if(mode != null && mode.equalsIgnoreCase("car")) {

                String links = ptEvent.getAttributes().get(ptEvent.ATTRIBUTE_LINK_IDS());

                String departureTime = ptEvent.getAttributes().get(ptEvent.ATTRIBUTE_DEPARTURE_TIME());
                String vehicleId = ptEvent.getAttributes().get(ptEvent.ATTRIBUTE_VEHICLE_ID());
                double time = ptEvent.getTime();
                String eventType = ptEvent.getEventType();
                RoutingModel.BeamLeg beamLeg = ptEvent.beamLeg();

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

                    Leg leg = populationFactory.createLeg(beamLeg.mode().matsimMode());
                    leg.setDepartureTime(beamLeg.startTime());
                    leg.setTravelTime(0);
                    List<Id<Link>> linkIds = new ArrayList<>();
                    int negCount = 0;
                    for(String link : links.split(",")) {

                        long osmLinkId = NetworkCoordinator.getOsmId(Integer.parseInt(link));

                        if(osmLinkId < 0) {
                            negCount++;
                            // The OSM id can be smaller than zero at the start and end of the route [transit stops vertex connected to the road network]
                        }else {
                            Id<Link> linkId = Id.createLinkId(osmLinkId);
                            linkIds.add(linkId);
                        }
                    }

                    if(negCount > 2){

                        // At most two negative vertex ids are expected, one at the beginning of the route and one at the end
                        // Something might be wrong
                        String errorMessage = "At most two negative vertex ids are expected, one at the beginning of the route and one at the end. " +
                                "Something might be wrong";
                        log.error(errorMessage);
                        //throw new Exception(errorMessage);
                    }
                    Route route = RouteUtils.createNetworkRoute(linkIds, network);
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
}

