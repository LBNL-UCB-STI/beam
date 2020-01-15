package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationStatsProvider;
import beam.analysis.physsim.*;
import beam.calibration.impl.example.CountsObjectiveFunction;
import beam.physsim.jdeqsim.cacc.CACCSettings;
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction;
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation;
import beam.router.BeamRouter;
import beam.router.FreeFlowTravelTime;
import beam.sim.BeamConfigChangesObservable;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.MetricsSupport;
import beam.sim.population.AttributesOfIndividual;
import beam.sim.population.PopulationAdjustment;
import beam.sim.population.PopulationAdjustment$;
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
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.Message;
import org.matsim.core.mobsim.jdeqsim.Road;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.households.Household;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * @author asif and rwaraich.
 */
public class AgentSimToPhysSimPlanConverter implements BasicEventHandler, MetricsSupport, IterationStatsProvider, Observer {

    public static final String CAR = "car";
    public static final String BUS = "bus";
    private static final String DUMMY_ACTIVITY = "DummyActivity";
    private static PhyssimCalcLinkStats linkStatsGraph;
    private static PhyssimCalcLinkSpeedStats linkSpeedStatsGraph;
    private static PhyssimCalcLinkSpeedDistributionStats linkSpeedDistributionStatsGraph;
    private static PhyssimNetworkLinkLengthDistribution physsimNetworkLinkLengthDistribution;
    private static PhyssimNetworkComparisonEuclideanVsLengthAttribute physsimNetworkEuclideanVsLengthAttribute;
    private final ActorRef router;
    private final OutputDirectoryHierarchy controlerIO;
    private final Logger log = LoggerFactory.getLogger(AgentSimToPhysSimPlanConverter.class);
    private final Scenario agentSimScenario;
    private Population jdeqsimPopulation;
    private TravelTime previousTravelTime;
    private BeamServices beamServices;
    private BeamConfigChangesObservable beamConfigChangesObservable;

    private AgentSimPhysSimInterfaceDebugger agentSimPhysSimInterfaceDebugger;

    private BeamConfig beamConfig;
    private final Random rand = MatsimRandom.getRandom();

    private final boolean agentSimPhysSimInterfaceDebuggerEnabled;

    private final List<CompletableFuture> completableFutures = new ArrayList<>();

    Map<String, Boolean> caccVehiclesMap = new TreeMap<>();


    private TravelTime prevTravelTime = new FreeFlowTravelTime();

    private final Map<Id<Person>, Household> personToHouseHold;

    private final Random rnd;

    public AgentSimToPhysSimPlanConverter(EventsManager eventsManager,
                                          TransportNetwork transportNetwork,
                                          OutputDirectoryHierarchy controlerIO,
                                          Scenario scenario,
                                          BeamServices beamServices,
                                          BeamConfigChangesObservable beamConfigChangesObservable) {
        eventsManager.addHandler(this);
        this.beamServices = beamServices;
        this.controlerIO = controlerIO;
        this.router = beamServices.beamRouter();
        this.beamConfig = beamServices.beamConfig();
        this.rand.setSeed(beamConfig.matsim().modules().global().randomSeed());
        this.beamConfigChangesObservable = beamConfigChangesObservable;
        agentSimScenario = scenario;
        agentSimPhysSimInterfaceDebuggerEnabled = beamConfig.beam().physsim().jdeqsim().agentSimPhysSimInterfaceDebugger().enabled();

        if (agentSimPhysSimInterfaceDebuggerEnabled) {
            log.warn("AgentSimPhysSimInterfaceDebugger is enabled");
            agentSimPhysSimInterfaceDebugger = new AgentSimPhysSimInterfaceDebugger(beamServices.geo(), transportNetwork);
        }

        preparePhysSimForNewIteration();

        linkStatsGraph = new PhyssimCalcLinkStats(agentSimScenario.getNetwork(), controlerIO, beamServices.beamConfig(),
                scenario.getConfig().travelTimeCalculator(),beamConfigChangesObservable);
        linkSpeedStatsGraph = new PhyssimCalcLinkSpeedStats(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        linkSpeedDistributionStatsGraph = new PhyssimCalcLinkSpeedDistributionStats(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        physsimNetworkLinkLengthDistribution = new PhyssimNetworkLinkLengthDistribution(agentSimScenario.getNetwork(),controlerIO,beamConfig);
        physsimNetworkEuclideanVsLengthAttribute = new PhyssimNetworkComparisonEuclideanVsLengthAttribute(agentSimScenario.getNetwork(),controlerIO,beamConfig);
        beamConfigChangesObservable.addObserver(this);

        personToHouseHold = scenario.getHouseholds().getHouseholds().values().stream().flatMap(h -> h.getMemberIds().stream().map(m -> new AbstractMap.SimpleEntry<Id<Person>, Household>(m, h)))
                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));

        rnd = new Random(beamConfig.matsim().modules().global().randomSeed());
    }


    private void preparePhysSimForNewIteration() {
        jdeqsimPopulation = PopulationUtils.createPopulation(agentSimScenario.getConfig());
    }


    private void setupActorsAndRunPhysSim(int iterationNumber) {
        RelaxationExperiment sim = null;
        switch (beamConfig.beam().physsim().relaxation().type()) {
            case "normal":
                sim = new Normal(beamConfig, agentSimScenario, jdeqsimPopulation,
                        beamServices,
                        controlerIO, caccVehiclesMap, beamConfigChangesObservable, iterationNumber, shouldWritePhysSimEvents(iterationNumber), rnd);
                break;
            case "experiment_2.0":
                sim = new Experiment_2_0(beamConfig, agentSimScenario, jdeqsimPopulation,
                        beamServices,
                        controlerIO, caccVehiclesMap, beamConfigChangesObservable, iterationNumber, shouldWritePhysSimEvents(iterationNumber), rnd);
                break;
            case "experiment_2.1":
                sim = new Experiment_2_1(beamConfig, agentSimScenario, jdeqsimPopulation,
                        beamServices,
                        controlerIO, caccVehiclesMap, beamConfigChangesObservable, iterationNumber, shouldWritePhysSimEvents(iterationNumber), rnd);
                break;
            case "experiment_3.0":
                sim = new Experiment_3_0(beamConfig, agentSimScenario, jdeqsimPopulation,
                        beamServices,
                        controlerIO, caccVehiclesMap, beamConfigChangesObservable, iterationNumber, shouldWritePhysSimEvents(iterationNumber), rnd);
                break;
            case "experiment_4.0":
                sim = new Experiment_4_0(beamConfig, agentSimScenario, jdeqsimPopulation,
                        beamServices,
                        controlerIO, caccVehiclesMap, beamConfigChangesObservable, iterationNumber, shouldWritePhysSimEvents(iterationNumber), rnd);
                break;
        }
        log.info("RelaxationExperiment is {}, type is {}", sim.getClass().getSimpleName(), beamConfig.beam().physsim().relaxation().type());
        TravelTime travelTimes = sim.run(prevTravelTime);
        // Safe travel time to reuse it on the next PhysSim iteration
        prevTravelTime = travelTimes;
//        PhysSimEventWriter eventWriter = null;
//        if (shouldWritePhysSimEvents(iterationNumber)) {
//            eventWriter = PhysSimEventWriter.apply(beamServices, jdeqsimEvents);
//            jdeqsimEvents.addHandler(eventWriter);
//        }
//        else {
//            if (beamConfig.beam().physsim().writeEventsInterval() < 1)
//                log.info("There will be no PhysSim events written because `beam.physsim.writeEventsInterval` is set to 0");
//            else
//                log.info("Skipping writing PhysSim events for iteration {}. beam.physsim.writeEventsInterval = {}", iterationNumber, beamConfig.beam().physsim().writeEventsInterval());
//        }

//        MutableScenario jdeqSimScenario = (MutableScenario) ScenarioUtils.createScenario(agentSimScenario.getConfig());
//        jdeqSimScenario.setNetwork(agentSimScenario.getNetwork());
//        jdeqSimScenario.setPopulation(jdeqsimPopulation);
//        EventsManager jdeqsimEvents = new EventsManagerImpl();
//        TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(agentSimScenario.getNetwork(), agentSimScenario.getConfig().travelTimeCalculator());
//        jdeqsimEvents.addHandler(travelTimeCalculator);
//        jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam().debug().debugEnabled()));
//
//        if (beamConfig.beam().physsim().writeMATSimNetwork()) {
//            createNetworkFile(jdeqSimScenario.getNetwork());
//        }
//
//        EventWriterXML_viaCompatible eventsWriterXML = null;
//        if (shouldWritePhysSimEvents(iterationNumber)) {
//
//            double eventsSampling = beamConfig.beam().physsim().eventsSampling();
//            boolean eventsForFullVersionOfVia = beamConfig.beam().physsim().eventsForFullVersionOfVia();
//            String fileName = controlerIO.getIterationFilename(iterationNumber, "physSimEvents.xml.gz");
//            eventsWriterXML = new EventWriterXML_viaCompatible(fileName, eventsForFullVersionOfVia , eventsSampling);
//            jdeqsimEvents.addHandler(eventsWriterXML);
//        }
//
//
//        RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction = null;
//        try {
//            if (beamConfig.beam().physsim().jdeqsim().cacc().enabled()) {
//                roadCapacityAdjustmentFunction = new Hao2018CaccRoadCapacityAdjustmentFunction(
//                        beamConfig,
//                        iterationNumber,
//                        controlerIO,
//                        this.beamConfigChangesObservable
//                );
//            }
//            org.matsim.core.mobsim.jdeqsim.JDEQSimulation jdeqSimulation = getJDEQSimulation(jdeqSimScenario,
//                    jdeqsimEvents, iterationNumber, beamServices.matsimServices().getControlerIO(),
//                    roadCapacityAdjustmentFunction);
//            linkStatsGraph.notifyIterationStarts(jdeqsimEvents, agentSimScenario.getConfig().travelTimeCalculator());
//
//            log.info("JDEQSim Start");
//            startSegment("jdeqsim-execution", "jdeqsim");
//            if (beamConfig.beam().debug().debugEnabled()) {
//                log.info(DebugLib.getMemoryLogMessage("Memory Use Before JDEQSim: "));
//            }
//
//            jdeqSimulation.run();
//        }
//        finally {
//            if (roadCapacityAdjustmentFunction != null) roadCapacityAdjustmentFunction.reset();
//        }

        if (beamConfig.beam().debug().debugEnabled()) {
            log.info(DebugLib.getMemoryLogMessage("Memory Use After JDEQSim: "));
        }

        endSegment("jdeqsim-execution", "jdeqsim");
        log.info("JDEQSim End");

        String objectiveFunction = beamConfig.beam().calibration().objectiveFunction();
        if (this.controlerIO != null
                && objectiveFunction.toLowerCase().contains("counts")) {
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
        // TravelTime travelTimes = travelTimeCalculator.getLinkTravelTimes();
        Map<String, double[]> map = TravelTimeCalculatorHelper.GetLinkIdToTravelTimeArray(links,
                travelTimes, maxHour);

        TravelTime freeFlow = new FreeFlowTravelTime();
        int nBins = 0;
        int nBinsWithUnexpectedlyLowSpeed = 0;
        for (Map.Entry<String, double[]> entry : map.entrySet()) {
            int hour = 0;
            Link link = agentSimScenario.getNetwork().getLinks().get(Id.createLinkId(entry.getKey()));
            for (double linkTravelTime : entry.getValue()) {
                double speed = link.getLength() / linkTravelTime;
                if (speed < beamConfig.beam().physsim().quick_fix_minCarSpeedInMetersPerSecond()) {
                    double linkTravelTime1 = travelTimes.getLinkTravelTime(link, hour * 60.0 * 60.0, null, null);
                    double freeFlowTravelTime = freeFlow.getLinkTravelTime(link, hour * 60.0 * 60.0, null, null);
                    log.debug("{} {} {}", linkTravelTime, linkTravelTime1, freeFlowTravelTime);
                    nBinsWithUnexpectedlyLowSpeed++;
                }
                hour++;
                nBins++;
            }
        }
        if (nBinsWithUnexpectedlyLowSpeed > 0) {
            log.error("Iteration {} had {} link speed bins (of {}) with speed smaller than {}.", iterationNumber, nBinsWithUnexpectedlyLowSpeed, nBins, beamConfig.beam().physsim().quick_fix_minCarSpeedInMetersPerSecond());
        }


        Integer startingIterationForTravelTimesMSA = beamConfig.beam().routing().startingIterationForTravelTimesMSA();
        if (startingIterationForTravelTimesMSA <= iterationNumber) {
            map = processTravelTime(links, map, maxHour);
            travelTimes = previousTravelTime;
        }


        router.tell(new BeamRouter.TryToSerialize(map), ActorRef.noSender());
        router.tell(new BeamRouter.UpdateTravelTimeRemote(map), ActorRef.noSender());
        //################################################################################################################
        router.tell(new BeamRouter.UpdateTravelTimeLocal(travelTimes), ActorRef.noSender());

        TravelTime finalTravelTimes = travelTimes;
        completableFutures.add(CompletableFuture.runAsync(() -> {
            linkStatsGraph.notifyIterationEnds(iterationNumber, finalTravelTimes);
            linkStatsGraph.clean();
        }));

        completableFutures.add(CompletableFuture.runAsync(() -> linkSpeedStatsGraph.notifyIterationEnds(iterationNumber, finalTravelTimes)));

        completableFutures.add(CompletableFuture.runAsync(() -> linkSpeedDistributionStatsGraph.notifyIterationEnds(iterationNumber, finalTravelTimes)));

        completableFutures.add(CompletableFuture.runAsync(() -> physsimNetworkLinkLengthDistribution.notifyIterationEnds(iterationNumber)));

        completableFutures.add(CompletableFuture.runAsync(() -> physsimNetworkEuclideanVsLengthAttribute.notifyIterationEnds(iterationNumber)));

//        if (shouldWritePhysSimEvents(iterationNumber)) {
//            assert eventWriter != null;
//            eventWriter.closeFile();
//        }

        Road.setAllRoads(null);
        Message.setEventsManager(null);
//        jdeqSimScenario.setNetwork(null);
//        jdeqSimScenario.setPopulation(null);

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

    public org.matsim.core.mobsim.jdeqsim.JDEQSimulation getJDEQSimulation(MutableScenario jdeqSimScenario, EventsManager jdeqsimEvents,
            int iterationNumber, OutputDirectoryHierarchy controlerIO, RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction) {
        JDEQSimConfigGroup config = new JDEQSimConfigGroup();
        double flowCapacityFactor = beamConfig.beam().physsim().flowCapacityFactor();

        config.setFlowCapacityFactor(flowCapacityFactor);
        config.setStorageCapacityFactor(beamConfig.beam().physsim().storageCapacityFactor());
        config.setSimulationEndTime(beamConfig.matsim().modules().qsim().endTime());

        org.matsim.core.mobsim.jdeqsim.JDEQSimulation jdeqSimulation = null;

        if (roadCapacityAdjustmentFunction != null) {
            log.info("CACC enabled");
            int caccCategoryRoadCount = 0;
            for (Link link : jdeqSimScenario.getNetwork().getLinks().values()) {
                if (roadCapacityAdjustmentFunction.isCACCCategoryRoad(link)) {
                    caccCategoryRoadCount++;
                }
            }
            log.info("caccCategoryRoadCount: " + caccCategoryRoadCount + " out of " + jdeqSimScenario.getNetwork().getLinks().values().size());

            CACCSettings caccSettings = new CACCSettings(
                    caccVehiclesMap, roadCapacityAdjustmentFunction
            );
            double speedAdjustmentFactor = beamConfig.beam().physsim().jdeqsim().cacc().speedAdjustmentFactor();
            double minimumRoadSpeedInMetersPerSecond = beamConfig.beam().physsim().jdeqsim().cacc().minimumRoadSpeedInMetersPerSecond();
            jdeqSimulation = new JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents, caccSettings, speedAdjustmentFactor, minimumRoadSpeedInMetersPerSecond);
        } else {
            log.info("CACC disabled");
            jdeqSimulation = new org.matsim.core.mobsim.jdeqsim.JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents);
        }

        return jdeqSimulation;
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


    public static boolean isPhyssimMode(String mode) {
        return mode.equalsIgnoreCase(CAR) || mode.equalsIgnoreCase(BUS);
    }

    @Override
    public void handleEvent(Event event) {

        if (agentSimPhysSimInterfaceDebuggerEnabled) {
            agentSimPhysSimInterfaceDebugger.handleEvent(event);
        }

        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pte = (PathTraversalEvent) event;
            String mode = pte.mode().value();

            // pt sampling
            // TODO: if requested, add beam.physsim.ptSamplingMode (pathTraversal | busLine), which controls if instead of filtering outWriter
            // pathTraversal, a busLine should be filtered out, avoiding jumping buses in visualization (but making traffic flows less precise).

            if (mode.equalsIgnoreCase(BUS) && rand.nextDouble() > beamConfig.beam().physsim().ptSampleSize()) {
                return;
            }
            if (isPhyssimMode(mode)) {
                double departureTime = pte.departureTime();
                String vehicleId = pte.vehicleId().toString();

                String vehicleType = pte.vehicleType();
                Id<BeamVehicleType> beamVehicleTypeId = Id.create(vehicleType, BeamVehicleType.class);
                boolean isCaccEnabled = beamServices.beamScenario().vehicleTypes().get(beamVehicleTypeId).get().isCaccEnabled();
                caccVehiclesMap.put(vehicleId, isCaccEnabled);

                // For every PathTraversalEvent which has PhysSim mode (CAR or BUS) we create
                // - If person does not exist, we create Person from `vehicleId`. For that person we create plan, set it to selected plan and add attributes from the original person
                // - Create leg
                // - Create dummy activity
                final Person person = initializePersonAndPlanIfNeeded(Id.createPersonId(vehicleId), Id.createPersonId(pte.driverId()));
                final Plan plan = person.getSelectedPlan();
                final Leg leg = createLeg(pte);

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

    private Person initializePersonAndPlanIfNeeded(Id<Person> vehicleId, Id<Person> driverId) {
        // Beam in PhysSim part (JDEQSim) simulates vehicles, not people!
        // So, we have to create _person_ who actually is vehicle.
        final Person alreadyInitedPerson = jdeqsimPopulation.getPersons().get(vehicleId);
        if (alreadyInitedPerson == null) {
            Person person = jdeqsimPopulation.getFactory().createPerson(vehicleId);
            Plan plan = jdeqsimPopulation.getFactory().createPlan();
            plan.setPerson(person);
            person.addPlan(plan);
            person.setSelectedPlan(plan);
            // person.getAttributes().putAttribute("vehicle_type", vehType);
            jdeqsimPopulation.addPerson(person);
            final Person originalPerson = agentSimScenario.getPopulation().getPersons().get(driverId);
            final Person personToCopyFrom = originalPerson == null ? agentSimScenario.getPopulation().getPersons().get(vehicleId) : originalPerson;
            // Try to copy person's attributes from original `agentSimScenario` to the created one. Attributes are important because they are used during R5 routing
            if (personToCopyFrom != null) {
                try {
                    Attributes attributes = personToCopyFrom.getAttributes();
                    Stream<String> keys = Arrays.stream(attributes.toString().split("\\{ key=")).filter(x -> x.contains(";")).map(z -> z.split(";")[0]);
                    keys.forEach(key -> {
                        person.getAttributes().putAttribute(key, attributes.getAttribute(key));
                    });
                    final Household hh = personToHouseHold.get(personToCopyFrom.getId());
                    final AttributesOfIndividual attributesOfIndividual = PopulationAdjustment$.MODULE$.createAttributesOfIndividual(beamServices.beamScenario(), beamServices.matsimServices().getScenario().getPopulation(), personToCopyFrom, hh);
                    person.getCustomAttributes().put(PopulationAdjustment.BEAM_ATTRIBUTES(), attributesOfIndividual);
                }
                catch (Exception ex) {
                    log.error("Could not create attributes for person " + vehicleId, ex);
                }
            }
            return person;
        }
        else {
            return alreadyInitedPerson;
        }
    }

    private Leg createLeg(PathTraversalEvent pte) {
        List<Id<Link>> linkIds = new ArrayList<>();

        for (Object linkObjId : pte.linkIdsJava()) {
            Id<Link> linkId = Id.createLinkId(linkObjId.toString());
            linkIds.add(linkId);
        }

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
        Leg leg = jdeqsimPopulation.getFactory().createLeg(CAR);
        leg.setDepartureTime(pte.departureTime());
        leg.setTravelTime(0);
        leg.setRoute(route);
        leg.getAttributes().putAttribute("travel_time", pte.arrivalTime() - pte.departureTime());
        leg.getAttributes().putAttribute("departure_time", pte.departureTime());
        leg.getAttributes().putAttribute("event_time", pte.time());
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


    public Map<String, double[]> processTravelTime(Collection<? extends Link> links, Map<String, double[]> currentTravelTimeMap, int maxHour) {
        int binSize = beamConfig.beam().agentsim().timeBinSize();
        TravelTime currentTravelTime = TravelTimeCalculatorHelper.CreateTravelTimeCalculator(binSize, currentTravelTimeMap);

        if (previousTravelTime == null) {
            previousTravelTime = currentTravelTime;
            return currentTravelTimeMap;
        } else {
            Map<String, double[]> map = TravelTimeCalculatorHelper.GetLinkIdToTravelTimeAvgArray(links, currentTravelTime, previousTravelTime, maxHour);
            TravelTime averageTravelTimes = TravelTimeCalculatorHelper.CreateTravelTimeCalculator(binSize, map);

            previousTravelTime = averageTravelTimes;
            return map;
        }
    }

    @Override
    public void update(Observable observable, Object o) {
        Tuple2 t = (Tuple2) o;
        this.beamConfig = (BeamConfig) t._2;
    }
}