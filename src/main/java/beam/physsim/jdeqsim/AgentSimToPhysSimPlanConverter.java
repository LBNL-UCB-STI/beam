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
import beam.router.r5.R5RoutingWorker;
import beam.sim.BeamServices;
import glokka.Registry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.Vehicle;
import scala.concurrent.Await;
import scala.util.Left;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Created by asif on 8/18/2017.
 */
public class AgentSimToPhysSimPlanConverter implements BasicEventHandler {

    Scenario scenario;
    Population population;
    PopulationFactory populationFactory;
    Map<Id<Vehicle>, Id<Person>> vehiclePersonMap = new HashMap<>();
    Network network;
    PlansConfigGroup.ActivityDurationInterpretation activityDurationInterpretation;
    Activity latestActivity;

    Map<Long, Person> persons = new HashMap<>();

    List<PathTraversalEvent> pathTraversalEventList = new ArrayList<>();
    BeamServices services;
    //ActorRef beamRouterRef;

    public AgentSimToPhysSimPlanConverter(BeamServices _services){

        this.services = _services;
        //beamRouterRef = this.services.beamRouter();
        Scenario _scenario = this.services.matsimServices().getScenario();
        // Is this factory connected to main factory loaded in BeamSim or a new factory
        //Scenario localScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig("C:/ns/beam-integration-project/model-inputs/beamville/beam.conf"));
        //System.out.println("Network loaded1 -> " + localScenario.getNetwork().getNodes().toString());

        scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        population = scenario.getPopulation();
        populationFactory = scenario.getPopulation().getFactory();
        activityDurationInterpretation = _scenario.getConfig().plans().getActivityDurationInterpretation();
        network = _scenario.getNetwork();

//        Long osmId = RouterApp.getOsmId(230);
//        System.out.println("Osm Id " +  osmId);
    }

    @Override
    public void reset(int iteration) {

        System.out.println(AgentSimToPhysSimPlanConverter.class.getName() + " -> ITERATION -> " + iteration);
        // send plans to physsim

        // we have to get physsim reference from registry for example
        // either for now have physsim as sub package in this same project called singlecpu
        System.out.println("pathTraversalEventList -> " + pathTraversalEventList.toString());
        System.out.println(getClass().getName() +  " -> Persons -> " + population.getPersons().toString());
        System.out.println(getClass().getName() +  " -> VehiclePersonMap -> " + vehiclePersonMap.toString());

        for(Person p : population.getPersons().values()){
            Plan plan = p.getSelectedPlan();
            Leg leg = (Leg)plan.getPlanElements().get(plan.getPlanElements().size() - 1);

            plan.addActivity(populationFactory.createActivityFromLinkId("dummy", leg.getRoute().getEndLinkId()));
        }
        initializeAndRun();
    }

    public void initializeAndRun(){
        /*
        Change the interface for jdeqSim so that it works with
        1. network, - We get network from scenario that we created in constructor
        2. collection of persons, - We have this after iteration completes
        3. getActivityDurationInterpretation - TODO Get this from somewhere
         */

        //Config config = ConfigUtils.loadConfig("C:/ns/matsim-project/matsim/examples/scenarios/equil/config.xml");
        //Scenario scenario = ScenarioUtils.loadScenario(config);
        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();

        //ActorSystem system = ActorSystem.create("PhysicalSimulation");


        //val schedulerFuture = services.registry ? Registry.Register("scheduler", Props(classOf[BeamAgentScheduler],3600*30.0, 300.0))
        //services.schedulerRef = Await.result(schedulerFuture, timeout.duration).asInstanceOf[Created].ref


        ActorRef registry = this.services.registry();
        try{
            ActorRef eventHandlerActorREF = registerActor(registry, "EventManagerActor", EventManagerActor.props());
            EventsManager eventsManager = new AkkaEventHandlerAdapter(eventHandlerActorREF);
            ActorRef jdeqsimActorREF = registerActor(registry, "JDEQSimActor", JDEQSimActor.props(jdeqSimConfigGroup,scenario,eventsManager, network, this.services.beamRouter()));

            jdeqsimActorREF.tell("start", ActorRef.noSender());
            eventHandlerActorREF.tell("registerJDEQSimREF", jdeqsimActorREF);
            //system.awaitTermination();
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

        // Logic will be like the below
        /*
        for every person we will have an entry in hashmap in the population ->
        we will check existing person and will add the activity and leg/routes to it or we will create new
        //////////////////////
        a) Any event handled will fall within a plan
        b) If a plan already exists corresponding to the person for the event,
            use that plan to gather activity and leg information from that event

        1. Create Person
        2. Create an activity from the event
        3. Create a leg from the event
        4. Check if a plan already exists for the person from the event
        4a. Use the existing plan
        4b. Otherwise create the new plan and use for future events for that person too
        5. We need plans for people using car mode

        // Load the network which we have created today beamville.xml
        // change the interface of jdeqsim, instead of scenario it will expect
        // a network, a collection of persons, a getActivityDurationInterpretation
        // Whenenver we initialize jdeqsim we will need these three things all this info
        // configgroup
        */
        /////////////////////////

        /*double time = event.getTime();
        String eventType = event.getEventType();
        long personId = Long.parseLong(event.getAttributes().get("person")); */
        System.out.println(AgentSimToPhysSimPlanConverter.class.getName() + " -> [Event] -> " + Event.class.getName() + event.toString() + ", " + event.getAttributes().keySet());

        if(event instanceof ActivityStartEvent) {
            // This is used to for handling the last activity of the day.
            ActivityStartEvent ase = ((ActivityStartEvent) event);
            String activityType = ase.getActType();
            Id<Link> linkId = ase.getLinkId();
            latestActivity = populationFactory.createActivityFromLinkId(activityType, linkId);


        }else if(event instanceof ActivityEndEvent) {
            // This is used to find out the last activity before the car leg (PathTraversalEvent)
            ActivityEndEvent aee = ((ActivityEndEvent) event);
            String activityType = aee.getActType();
            Id<Link> linkId = aee.getLinkId();
            latestActivity = populationFactory.createActivityFromLinkId(activityType, linkId);

//        }else if(event instanceof PersonEntersVehicleEvent){
//
//            // add person and vehicle to person vehicle map
//            PersonEntersVehicleEvent pevEvent = (PersonEntersVehicleEvent)event;
//            vehiclePersonMap.put(pevEvent.getVehicleId(), pevEvent.getPersonId());
//            System.out.println(getClass().getName() + "- " + PersonEntersVehicleEvent.class.getName() +  " -> VehiclePersonMap -> " + vehiclePersonMap.toString());
//        }else if(event instanceof PersonLeavesVehicleEvent) {
//            // remove person and vehicle from person vehicle map
//            PersonLeavesVehicleEvent pevEvent = (PersonLeavesVehicleEvent)event;
//            Id<Person> personId = vehiclePersonMap.get(pevEvent.getVehicleId());
//            if(personId != null && pevEvent.getPersonId() == personId){
//                vehiclePersonMap.remove(pevEvent.getVehicleId());
//            }
//            System.out.println(getClass().getName() + "- " + PersonLeavesVehicleEvent.class.getName() + " -> VehiclePersonMap -> " + vehiclePersonMap.toString());
        }else if(event instanceof PathTraversalEvent){
            PathTraversalEvent ptEvent = (PathTraversalEvent)event;
            //System.out.println(AgentSimToPhysSimPlanConverter.class.getName() + " -> PathTraversalEvent [Event] -> " + Event.class.getName() + event.toString() + ", " + event.getAttributes().keySet());
            System.out.println(AgentSimToPhysSimPlanConverter.class.getName() + " -> PathTraversalEvent [ptEvent] -> " + PathTraversalEvent.class.getName() + ptEvent.toString() + ", " + ptEvent.getAttributes().keySet());

            String mode = ptEvent.getAttributes().get(ptEvent.ATTRIBUTE_MODE());

            if(mode != null && mode.equalsIgnoreCase("car")) {

                pathTraversalEventList.add(ptEvent);

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

                //System.out.println("mode " + beamMode.matsimMode() + ", " + beamMode.otpMode() + ", " + beamMode.r5Mode());
                //System.out.println("mode='" + beamMode.matsimMode() + ", dep_time='" + beamLeg.startTime());

                //Id<Vehicle> vehicleId1 = Id.createVehicleId(vehicleId);
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
                    for(String link : links.split(",")) {

                        //long osmLinkId = R5RoutingWorker.transportNetwork().streetLayer.edgeStore.getCursor(Integer.parseInt(link)).getOSMID();
                        long osmLinkId = R5RoutingWorker.getOsmId(Integer.parseInt(link));

                        Id<Link> linkId = Id.createLinkId(osmLinkId);
                        linkIds.add(linkId);
                    }
                    Route route = RouteUtils.createNetworkRoute(linkIds, network);
                    leg.setRoute(route);


                    latestActivity.setLinkId(route.getStartLinkId());
                    latestActivity.setEndTime(beamLeg.startTime());

                    Person person = null;
                    if (personAlreadyExist) {
                        person = population.getPersons().get(personId);

                        Plan plan = person.getSelectedPlan();
                        plan.addActivity(latestActivity);
                        plan.addLeg(leg);


                    } else {
                        person = populationFactory.createPerson(personId);

                        Plan plan = populationFactory.createPlan();
                        plan.addActivity(latestActivity);
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

