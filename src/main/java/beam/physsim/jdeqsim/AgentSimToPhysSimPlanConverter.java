package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationStatsProvider;
import beam.analysis.physsim.PhyssimCalcLinkSpeedDistributionStats;
import beam.analysis.physsim.PhyssimCalcLinkSpeedStats;
import beam.analysis.physsim.PhyssimCalcLinkStats;
import beam.analysis.via.EventWriterXML_viaCompatible;
import beam.calibration.impl.example.CountsObjectiveFunction;
import beam.router.BeamRouter;
import beam.router.r5.R5RoutingWorker$;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.MetricsSupport;
import beam.utils.DebugLib;
import beam.utils.TravelTimeCalculatorHelper;
import com.conveyal.r5.transit.TransportNetwork;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimulation;
import org.matsim.core.mobsim.jdeqsim.Message;
import org.matsim.core.mobsim.jdeqsim.Road;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * @author asif and rwaraich.
 */
public class AgentSimToPhysSimPlanConverter implements BasicEventHandler, MetricsSupport, IterationStatsProvider {

    public static final String CAR = "car";
    public static final String BUS = "bus";
    private static final String DUMMY_ACTIVITY = "DummyActivity";
    private static PhyssimCalcLinkStats linkStatsGraph;
    private static PhyssimCalcLinkSpeedStats linkSpeedStatsGraph;
    private static PhyssimCalcLinkSpeedDistributionStats linkSpeedDistributionStatsGraph;
    private final ActorRef router;
    private final OutputDirectoryHierarchy controlerIO;
    private final Logger log = LoggerFactory.getLogger(AgentSimToPhysSimPlanConverter.class);
    private final Scenario agentSimScenario;
    private Population jdeqsimPopulation;
    private TravelTime previousTravelTime;


    private AgentSimPhysSimInterfaceDebugger agentSimPhysSimInterfaceDebugger;

    private final BeamConfig beamConfig;
    private final Random rand = MatsimRandom.getRandom();

    private final boolean agentSimPhysSimInterfaceDebuggerEnabled;

    private final List<CompletableFuture> completableFutures = new ArrayList<>();

    public AgentSimToPhysSimPlanConverter(EventsManager eventsManager,
                                          TransportNetwork transportNetwork,
                                          OutputDirectoryHierarchy controlerIO,
                                          Scenario scenario,
                                          BeamServices beamServices) {

        eventsManager.addHandler(this);
        this.controlerIO = controlerIO;
        this.router = beamServices.beamRouter();
        this.beamConfig = beamServices.beamConfig();
        this.rand.setSeed(beamConfig.matsim().modules().global().randomSeed());
        agentSimScenario = scenario;
        agentSimPhysSimInterfaceDebuggerEnabled = beamConfig.beam().physsim().jdeqsim().agentSimPhysSimInterfaceDebugger().enabled();

        if (agentSimPhysSimInterfaceDebuggerEnabled) {
            log.warn("AgentSimPhysSimInterfaceDebugger is enabled");
            agentSimPhysSimInterfaceDebugger = new AgentSimPhysSimInterfaceDebugger(beamServices.geo(), transportNetwork);
        }

        preparePhysSimForNewIteration();

        linkStatsGraph = new PhyssimCalcLinkStats(agentSimScenario.getNetwork(), controlerIO, beamServices.beamConfig(),
                scenario.getConfig().travelTimeCalculator());
        linkSpeedStatsGraph = new PhyssimCalcLinkSpeedStats(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        linkSpeedDistributionStatsGraph = new PhyssimCalcLinkSpeedDistributionStats(agentSimScenario.getNetwork(), controlerIO, beamConfig);
    }


    private void preparePhysSimForNewIteration() {
        jdeqsimPopulation = PopulationUtils.createPopulation(agentSimScenario.getConfig());
    }


    private void setupActorsAndRunPhysSim(int iterationNumber) {
        MutableScenario jdeqSimScenario = (MutableScenario) ScenarioUtils.createScenario(agentSimScenario.getConfig());
        jdeqSimScenario.setNetwork(agentSimScenario.getNetwork());
        jdeqSimScenario.setPopulation(jdeqsimPopulation);
        EventsManager jdeqsimEvents = new EventsManagerImpl();
        TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(agentSimScenario.getNetwork(), agentSimScenario.getConfig().travelTimeCalculator());
        jdeqsimEvents.addHandler(travelTimeCalculator);
        jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam().debug().debugEnabled()));

        if (beamConfig.beam().physsim().writeMATSimNetwork()) {
            createNetworkFile(jdeqSimScenario.getNetwork());
        }

        EventWriterXML_viaCompatible eventsWriterXML = null;
        if (shouldWritePhysSimEvents(iterationNumber)) {

            eventsWriterXML = new EventWriterXML_viaCompatible(controlerIO.getIterationFilename(iterationNumber, "physSimEvents.xml.gz"), beamConfig.beam().physsim().eventsForFullVersionOfVia());
            jdeqsimEvents.addHandler(eventsWriterXML);
        }

        JDEQSimConfigGroup config = new JDEQSimConfigGroup();
        config.setFlowCapacityFactor(beamConfig.beam().physsim().flowCapacityFactor());
        config.setStorageCapacityFactor(beamConfig.beam().physsim().storageCapacityFactor());
        config.setSimulationEndTime(beamConfig.matsim().modules().qsim().endTime());
        JDEQSimulation jdeqSimulation = new JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents);

        linkStatsGraph.notifyIterationStarts(jdeqsimEvents,  agentSimScenario.getConfig().travelTimeCalculator());

        log.info("JDEQSim Start");
        startSegment("jdeqsim-execution", "jdeqsim");
        if (beamConfig.beam().debug().debugEnabled()) {
            log.info(DebugLib.gcAndGetMemoryLogMessage("Memory Use Before JDEQSim (after GC): "));
        }

        jdeqSimulation.run();

        if (beamConfig.beam().debug().debugEnabled()) {
            log.info(DebugLib.gcAndGetMemoryLogMessage("Memory Use After JDEQSim (after GC): "));
        }

        endSegment("jdeqsim-execution", "jdeqsim");
        log.info("JDEQSim End");

        String objectiveFunction = beamConfig.beam().calibration().objectiveFunction();
        if (this.controlerIO != null
                && (objectiveFunction.equals("CountsObjectiveFunction")
                || objectiveFunction.equals("ModeChoiceAndCountsObjectiveFunction"))) {
            try {
                String outPath =
                        controlerIO
                                .getIterationFilename(iterationNumber, "countscompare.txt");
                Double countsError = CountsObjectiveFunction.evaluateFromRun(outPath);
                log.info("counts Error: " + countsError);
            } catch (Exception e) {
                log.error("exception {}", e.getMessage());
            }
        }

        // I don't use single class `UpdateTravelTime` here and make decision in `BeamRouter` because
        // below we have `linkStatsGraph.notifyIterationEnds` call which internally will call `BeamCalcLinkStats.addData`
        // which may change an internal state of travel time calculator (and it happens concurrently in CompletableFuture)
        //################################################################################################################
        Collection<? extends Link> links = agentSimScenario.getNetwork().getLinks().values();
        int maxHour = (int) TimeUnit.SECONDS.toHours(agentSimScenario.getConfig().travelTimeCalculator().getMaxTime());
        TravelTime travelTimes = travelTimeCalculator.getLinkTravelTimes();
        Map<String, double[]> map = TravelTimeCalculatorHelper.GetLinkIdToTravelTimeArray(links,
                travelTimes, maxHour);

        Integer startingIterationForTravelTimesMSA = beamConfig.beam().routing().startingIterationForTravelTimesMSA();
        if(startingIterationForTravelTimesMSA <= iterationNumber){
            map = processTravelTime(links, map, maxHour);
            travelTimes = previousTravelTime;
        }

        router.tell(new BeamRouter.TryToSerialize(map), ActorRef.noSender());
        router.tell(new BeamRouter.UpdateTravelTimeRemote(map), ActorRef.noSender());
        //################################################################################################################
        router.tell(new BeamRouter.UpdateTravelTimeLocal(travelTimes), ActorRef.noSender());

        completableFutures.add(CompletableFuture.runAsync(() -> {
            linkStatsGraph.notifyIterationEnds(iterationNumber, travelTimeCalculator);
            linkStatsGraph.clean();
        }));

        completableFutures.add(CompletableFuture.runAsync(() -> linkSpeedStatsGraph.notifyIterationEnds(iterationNumber, travelTimeCalculator)));


        completableFutures.add(CompletableFuture.runAsync(() -> linkSpeedDistributionStatsGraph.notifyIterationEnds(iterationNumber, travelTimeCalculator)));

        if (shouldWritePhysSimEvents(iterationNumber)) {
            assert eventsWriterXML != null;
            eventsWriterXML.closeFile();
        }

        Road.setAllRoads(null);
        Message.setEventsManager(null);
        jdeqSimScenario.setNetwork(null);
        jdeqSimScenario.setPopulation(null);

        if (iterationNumber == beamConfig.matsim().modules().controler().lastIteration()) {
            try {
                CompletableFuture allOfLinStatFutures = CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0]));
                log.info("Waiting started on link stats file dump.");
                allOfLinStatFutures.get(20, TimeUnit.MINUTES);
                log.info("Link stats file dump completed.");

            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.error("Error while generating link stats.", e);
            }
        }

    }

    private boolean shouldWritePhysSimEvents(int iterationNumber) {
        return shouldWriteInIteration(iterationNumber, beamConfig.beam().physsim().writeEventsInterval());
    }

    private boolean shouldWritePlans(int iterationNumber) {
        return shouldWriteInIteration(iterationNumber, beamConfig.beam().physsim().writePlansInterval());
    }

    private boolean shouldWriteInIteration(int iterationNumber, int interval) {
        return interval == 1 || (interval > 0 && iterationNumber % interval == 0);
    }

    private void createNetworkFile(Network network) {
        String physSimNetworkFilePath = controlerIO.getOutputFilename("physSimNetwork.xml.gz");
        if (!(new File(physSimNetworkFilePath)).exists()) {
            completableFutures.add(CompletableFuture.runAsync(() -> NetworkUtils.writeNetwork(network, physSimNetworkFilePath)));
        }
    }

    private void writePhyssimPlans(IterationEndsEvent event) {
        if (shouldWritePlans(event.getIteration())) {
            final String plansFilename = controlerIO.getIterationFilename(event.getIteration(), "physsimPlans.xml.gz");
            completableFutures.add(CompletableFuture.runAsync(() -> new PopulationWriter(jdeqsimPopulation).write(plansFilename)));
        }
    }


    public static boolean isPhyssimMode(String mode){
        return mode.equalsIgnoreCase(CAR) || mode.equalsIgnoreCase(BUS);
    }

    @Override
    public void handleEvent(Event event) {

        if (agentSimPhysSimInterfaceDebuggerEnabled) {
            agentSimPhysSimInterfaceDebugger.handleEvent(event);
        }

        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pathTraversalEvent = (PathTraversalEvent) event;
            Map<String, String> eventAttributes = pathTraversalEvent.getAttributes();
            String mode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);

            // pt sampling
            // TODO: if requested, add beam.physsim.ptSamplingMode (pathTraversal | busLine), which controls if instead of filtering outWriter
            // pathTraversal, a busLine should be filtered outWriter, avoiding jumping buses in visualization (but making traffic flows less precise).

            if (mode.equalsIgnoreCase(BUS) && rand.nextDouble() > beamConfig.beam().physsim().ptSampleSize()) {
                return;
            }


            if (isPhyssimMode(mode)) {

                String links = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LINK_IDS);
                double departureTime = Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME));
                String vehicleId = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);

                Id<Person> personId = Id.createPersonId(vehicleId);
                initializePersonAndPlanIfNeeded(personId);

                // add previous activity and leg to plan
                Person person = jdeqsimPopulation.getPersons().get(personId);
                Plan plan = person.getSelectedPlan();
                Leg leg = createLeg(CAR, links, departureTime);

                if (leg == null) {
                    return; // dont't process leg further, if empty
                }

                Activity previousActivity = jdeqsimPopulation.getFactory().createActivityFromLinkId(DUMMY_ACTIVITY, leg.getRoute().getStartLinkId());
                previousActivity.setEndTime(departureTime);
                plan.addActivity(previousActivity);
                plan.addLeg(leg);
            }
        }
    }

    private void initializePersonAndPlanIfNeeded(Id<Person> personId) {
        if (!jdeqsimPopulation.getPersons().containsKey(personId)) {
            Person person = jdeqsimPopulation.getFactory().createPerson(personId);
            Plan plan = jdeqsimPopulation.getFactory().createPlan();
            plan.setPerson(person);
            person.addPlan(plan);
            person.setSelectedPlan(plan);
            jdeqsimPopulation.addPerson(person);
        }
    }

    private Leg createLeg(String mode, String links, double departureTime) {
        List<Id<Link>> linkIds = new ArrayList<>();

        for (String link : links.equals("") ? new String[]{} : links.split(",")) {
            Id<Link> linkId = Id.createLinkId(link.trim());
            linkIds.add(linkId);
        }

        // hack: removing non-road links from route
        // TODO: debug problem properly, so that no that no events for physsim contain non-road links
        Map<Id<Link>, ? extends Link> networkLinks = agentSimScenario.getNetwork().getLinks();
        for (Id<Link> linkId : linkIds) {
            if (!networkLinks.containsKey(linkId)) {
                throw new RuntimeException("Link not found: " + linkId);
            }
        }

        if (linkIds.size() == 0) {
            return null;
        }
        // end of hack

        Route route = RouteUtils.createNetworkRoute(linkIds, agentSimScenario.getNetwork());
        Leg leg = jdeqsimPopulation.getFactory().createLeg(mode);
        leg.setDepartureTime(departureTime);
        leg.setTravelTime(0);
        leg.setRoute(route);
        return leg;
    }

    public void startPhysSim(IterationEndsEvent iterationEndsEvent) {
        //
        createLastActivityOfDayForPopulation();
        writePhyssimPlans(iterationEndsEvent);

        long start = System.currentTimeMillis();
        setupActorsAndRunPhysSim(iterationEndsEvent.getIteration());
        log.info("PhysSim for iteration {} took {} ms", iterationEndsEvent.getIteration(), System.currentTimeMillis() - start);
        preparePhysSimForNewIteration();
    }

    private void createLastActivityOfDayForPopulation() {
        for (Person p : jdeqsimPopulation.getPersons().values()) {
            Plan plan = p.getSelectedPlan();
            if (!plan.getPlanElements().isEmpty()) {
                Leg leg = (Leg) plan.getPlanElements().get(plan.getPlanElements().size() - 1);
                plan.addActivity(jdeqsimPopulation.getFactory().createActivityFromLinkId(DUMMY_ACTIVITY, leg.getRoute().getEndLinkId()));
            }
        }
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        return new HashMap<>();
    }


    ////
    public Map<String, double[]> processTravelTime(Collection<? extends Link> links, Map<String, double[]> currentTravelTimeMap, int maxHour){
        int binSize = beamConfig.beam().agentsim().timeBinSize();
        TravelTime currentTravelTime = TravelTimeCalculatorHelper.CreateTravelTimeCalculator(binSize, currentTravelTimeMap);

        if(previousTravelTime == null){
            previousTravelTime = currentTravelTime;
            return currentTravelTimeMap;
        }else{
            Map<String, double[]> map = TravelTimeCalculatorHelper.GetLinkIdToTravelTimeAvgArray(links, currentTravelTime, previousTravelTime, maxHour);
            TravelTime averageTravelTimes = TravelTimeCalculatorHelper.CreateTravelTimeCalculator(binSize, map);

            previousTravelTime = averageTravelTimes;
            return map;
        }
    }
}