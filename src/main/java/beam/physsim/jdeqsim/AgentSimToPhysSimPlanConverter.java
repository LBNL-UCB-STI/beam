package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import akka.japi.Pair;
import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationStatsProvider;
import beam.analysis.physsim.*;
import beam.calibration.impl.example.CountsObjectiveFunction;
import beam.physsim.jdeqsim.cacc.CACCSettings;
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.Hao2018CaccRoadCapacityAdjustmentFunction;
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction;
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation;
import beam.physsim.routingTool.*;
import beam.router.BeamRouter;
import beam.router.FreeFlowTravelTime;
import beam.sim.BeamConfigChangesObservable;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.MetricsSupport;
import beam.utils.DebugLib;
import beam.utils.TravelTimeCalculatorHelper;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.Files;
import com.vividsolutions.jts.geom.Coordinate;
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
import org.matsim.core.mobsim.jdeqsim.Message;
import org.matsim.core.mobsim.jdeqsim.Road;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.Tuple3;
import scala.collection.JavaConverters;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


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

    private final Map<String, Boolean> caccVehiclesMap = new TreeMap<>();

    private final List<PathTraversalEvent> traversalEventsForPhysSimulation = new LinkedList<>();

    private final RoutingToolWrapper routingToolWrapper;

    public AgentSimToPhysSimPlanConverter(EventsManager eventsManager,
                                          TransportNetwork transportNetwork,
                                          OutputDirectoryHierarchy controlerIO,
                                          Scenario scenario,
                                          BeamServices beamServices,
                                          BeamConfigChangesObservable beamConfigChangesObservable,
                                          RoutingToolWrapper routingToolWrapper) {
        this.routingToolWrapper = routingToolWrapper;
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
                scenario.getConfig().travelTimeCalculator(), beamConfigChangesObservable);
        linkSpeedStatsGraph = new PhyssimCalcLinkSpeedStats(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        linkSpeedDistributionStatsGraph = new PhyssimCalcLinkSpeedDistributionStats(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        physsimNetworkLinkLengthDistribution = new PhyssimNetworkLinkLengthDistribution(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        physsimNetworkEuclideanVsLengthAttribute = new PhyssimNetworkComparisonEuclideanVsLengthAttribute(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        beamConfigChangesObservable.addObserver(this);
    }


    private void preparePhysSimForNewIteration() {
        jdeqsimPopulation = PopulationUtils.createPopulation(agentSimScenario.getConfig());
    }


    private void setupActorsAndRunPhysSim(int iterationNumber) {
//        MutableScenario jdeqSimScenario = (MutableScenario) ScenarioUtils.createScenario(agentSimScenario.getConfig());
//        jdeqSimScenario.setNetwork(agentSimScenario.getNetwork());
//        jdeqSimScenario.setPopulation(jdeqsimPopulation);

//        jdeqSimScenario.getPopulation().getPersons().values().stream().findAny().get().getSelectedPlan().getPlanElements().forEach(p->{
//            if (p instanceof Activity){
//                Activity ac = (Activity)p;
//                ac.
//            }
//        });

        EventsManager jdeqsimEvents = new EventsManagerImpl();
        TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(agentSimScenario.getNetwork(), agentSimScenario.getConfig().travelTimeCalculator());
//        jdeqsimEvents.addHandler(travelTimeCalculator);
//        jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam().debug().debugEnabled()));

        if (beamConfig.beam().physsim().writeMATSimNetwork()) {
            createNetworkFile(agentSimScenario.getNetwork());
        }

        PhysSimEventWriter eventWriter = null;
        if (shouldWritePhysSimEvents(iterationNumber)) {
            eventWriter = PhysSimEventWriter.apply(beamServices, jdeqsimEvents);
//            jdeqsimEvents.addHandler(eventWriter);
        } else {
            if (beamConfig.beam().physsim().writeEventsInterval() < 1)
                log.info("There will be no PhysSim events written because `beam.physsim.writeEventsInterval` is set to 0");
            else
                log.info("Skipping writing PhysSim events for iteration {}. beam.physsim.writeEventsInterval = {}", iterationNumber, beamConfig.beam().physsim().writeEventsInterval());
        }


        RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction = null;
        try {
            if (beamConfig.beam().physsim().jdeqsim().cacc().enabled()) {
                roadCapacityAdjustmentFunction = new Hao2018CaccRoadCapacityAdjustmentFunction(
                        beamConfig,
                        iterationNumber,
                        controlerIO,
                        this.beamConfigChangesObservable
                );
            }
//            org.matsim.core.mobsim.jdeqsim.JDEQSimulation jdeqSimulation = getJDEQSimulation(jdeqSimScenario,
//                    jdeqsimEvents, iterationNumber, beamServices.matsimServices().getControlerIO(),
//                    roadCapacityAdjustmentFunction);
//            linkStatsGraph.notifyIterationStarts(jdeqsimEvents, agentSimScenario.getConfig().travelTimeCalculator());

//            log.info("JDEQSim Start");
//            startMeasuring("jdeqsim-execution:jdeqsim", Metrics.ShortLevel());
//            if (beamConfig.beam().debug().debugEnabled()) {
//                log.info(DebugLib.getMemoryLogMessage("Memory Use Before JDEQSim: "));
//            }

//            jdeqSimulation.run();
        } finally {
            if (roadCapacityAdjustmentFunction != null) roadCapacityAdjustmentFunction.reset();
        }

        if (beamConfig.beam().debug().debugEnabled()) {
            log.info(DebugLib.getMemoryLogMessage("Memory Use After JDEQSim: "));
        }

//        stopMeasuring("jdeqsim-execution:jdeqsim");
//        log.info("JDEQSim End");

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
        int maxHour = (int) TimeUnit.SECONDS.toHours(agentSimScenario.getConfig().travelTimeCalculator().getMaxTime());


        Collection<? extends Link> links = agentSimScenario.getNetwork().getLinks().values();
        Map<Integer, ? extends Link> id2Link = links.stream()
                .collect(Collectors.toMap(x -> Integer.parseInt(x.getId().toString()), x -> x));


        RoutingToolGraph graph = RoutingToolsGraphReaderImpl.read(routingToolWrapper.generateGraph());
        Map<Coordinate, Long> coordinateToRTVertexId = JavaConverters.asJavaCollection(graph.vertices()).stream()
                .collect(Collectors.toMap(Vertex::coordinate, Vertex::id));
        OsmInfoHolder osmInfoHolder = new OsmInfoHolder(beamServices);


        Map<Integer, List<PathTraversalEvent>> hour2Events = traversalEventsForPhysSimulation.stream()
                .collect(Collectors.groupingBy(x -> x.departureTime() / 3600));

        Map<Integer, Map<Long, DoubleSummaryStatistics>> hour2Way2Speeds = hour2Events.entrySet().stream().map(mapEntry -> {
            Integer hour = mapEntry.getKey();
            List<PathTraversalEvent> events = mapEntry.getValue();

            List<Pair<Long, Long>> ods = events.stream().map(e -> {
                Link firstLink = id2Link.get(e.linkIds().head());
                Link secondLink = id2Link.get(e.linkIds().last());
                long firstWayId = linkWayId(firstLink);
                long secondWayId = linkWayId(secondLink);

                List<Coordinate> firstLinkCoordinates = getCoordinatesForWayId(osmInfoHolder, firstWayId);

                Coordinate origin = firstLinkCoordinates.get(0);
                Coordinate destination;

                if (firstWayId == secondWayId) {
                    destination = firstLinkCoordinates.get(firstLinkCoordinates.size() - 1);
                } else {
                    List<Coordinate> secondLinkCoordinates = getCoordinatesForWayId(osmInfoHolder, secondWayId);
                    destination = secondLinkCoordinates.get(secondLinkCoordinates.size() - 1);
                }

                Long firstId = coordinateToRTVertexId.get(origin);
                if (firstId == null) {
//                    System.out.println("Failed to find coordinate " + origin.toString());
                    Map.Entry<Coordinate, Long> closest = coordinateToRTVertexId.entrySet().stream()
                            .min(Comparator.comparingDouble(x -> x.getKey().distance(origin)))
                            .get();
//                    System.out.println("Found closest by distance " + closest.getKey().toString());
                    firstId = closest.getValue();
                }
                Long secondId = coordinateToRTVertexId.get(destination);
                if (secondId == null) {
//                    System.out.println("Failed to find coordinate " + destination.toString());
                    Map.Entry<Coordinate, Long> closest = coordinateToRTVertexId.entrySet().stream()
                            .min(Comparator.comparingDouble(x -> x.getKey().distance(destination)))
                            .get();
//                    System.out.println("Found closest by distance " + closest.getKey().toString());
                    secondId = closest.getValue();
                }

                return new Pair<>(firstId, secondId);
            }).collect(Collectors.toList());

            System.out.println("Generated " + ods.size() + "ods");

            routingToolWrapper.generateOd(ods);

            System.out.println("Running for hour " + hour);
            Tuple3<File, File, File> assignResult = routingToolWrapper.assignTraffic();
            Map<Long, DoubleSummaryStatistics> wayId2TravelTime;
            try {
                wayId2TravelTime = Files.readLines(assignResult._1(), Charset.defaultCharset()).stream()
                        .skip(2)
                        .map(x -> x.split(","))
                        // picking only result of 10th iteration
                        .filter(x -> x[0].equals("10"))
                        .map(x -> new Pair<>(Long.parseLong(x[4]), Integer.parseInt(x[3]) / 10.0))
                        .collect(Collectors.groupingBy(Pair::first, Collectors.summarizingDouble(Pair::second)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return new Pair<>(hour, wayId2TravelTime);
        }).collect(Collectors.toMap(Pair::first, Pair::second));

        Map<String, double[]> finalMap = new HashMap<>();
        Map<String, double[]> map = finalMap;


        int totalNumberOfLinks = links.size();
        AtomicInteger linksFailedToResolve = new AtomicInteger(0);

        links.stream().filter(x -> x.getAttributes().getAttribute("origid") == null).forEach(x -> {
            linksFailedToResolve.incrementAndGet();
            finalMap.put(x.getId().toString(), new double[31]);
        });

        links.stream().filter(x -> x.getAttributes().getAttribute("origid") != null)
                .collect(Collectors.groupingBy(x -> linkWayId(x)))
                .forEach((wayId, linksInWay) -> linksInWay.forEach(link -> {
                    double[] speedsByHour = new double[31];
                    boolean atLeastOneHour = false;
                    for (int hour = 0; hour <= 30; hour++) {
                        Map<Long, DoubleSummaryStatistics> way2Speed = hour2Way2Speeds.get(hour);
                        if (way2Speed == null || way2Speed.get(wayId) == null) {
//                            System.out.println("Failed to get speed for " + hour + " hour and wayId " + wayId);
                            continue;
                        }
                        atLeastOneHour = true;
                        speedsByHour[hour] = way2Speed
                                .get(wayId).getSum() / linksInWay.size();
                    }
                    if (!atLeastOneHour) linksFailedToResolve.incrementAndGet();
                    finalMap.put(link.getId().toString(), speedsByHour);
                }));

        System.out.println("total: " + totalNumberOfLinks + ", failed: " + linksFailedToResolve.get());

        TravelTime travelTimes = TravelTimeCalculatorHelper.CreateTravelTimeCalculator(beamConfig.beam().agentsim().timeBinSize(), map);

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

        completableFutures.add(CompletableFuture.runAsync(() -> {
            linkStatsGraph.notifyIterationEnds(iterationNumber, travelTimeCalculator);
            linkStatsGraph.clean();
        }));

        completableFutures.add(CompletableFuture.runAsync(() -> linkSpeedStatsGraph.notifyIterationEnds(iterationNumber, travelTimeCalculator)));

        completableFutures.add(CompletableFuture.runAsync(() -> linkSpeedDistributionStatsGraph.notifyIterationEnds(iterationNumber, travelTimeCalculator)));

        completableFutures.add(CompletableFuture.runAsync(() -> physsimNetworkLinkLengthDistribution.notifyIterationEnds(iterationNumber)));

        completableFutures.add(CompletableFuture.runAsync(() -> physsimNetworkEuclideanVsLengthAttribute.notifyIterationEnds(iterationNumber)));

        if (shouldWritePhysSimEvents(iterationNumber)) {
            assert eventWriter != null;
            eventWriter.closeFile();
        }

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
        traversalEventsForPhysSimulation.clear();
    }

    private List<Coordinate> getCoordinatesForWayId(OsmInfoHolder osmInfoHolder, long firstWayId) {
        return JavaConverters.asJavaCollection(
                osmInfoHolder.id2NodeIds().apply(firstWayId)
        ).stream().map(x -> osmInfoHolder.id2NodeCoordinate().getOrElse(x, () -> new Coordinate(0.0, 0.0)))
                .collect(Collectors.toList());
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
            double adjustedMinimumRoadSpeedInMetersPerSecond = beamConfig.beam().physsim().jdeqsim().cacc().adjustedMinimumRoadSpeedInMetersPerSecond();
            jdeqSimulation = new JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents, caccSettings, speedAdjustmentFactor, adjustedMinimumRoadSpeedInMetersPerSecond);
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
                traversalEventsForPhysSimulation.add((PathTraversalEvent) event);


                double departureTime = pte.departureTime();
                String vehicleId = pte.vehicleId().toString();

                String vehicleType = pte.vehicleType();
                Id<BeamVehicleType> beamVehicleTypeId = Id.create(vehicleType, BeamVehicleType.class);
                boolean isCaccEnabled = beamServices.beamScenario().vehicleTypes().get(beamVehicleTypeId).get().isCaccEnabled();
                caccVehiclesMap.put(vehicleId, isCaccEnabled);

                Id<Person> personId = Id.createPersonId(vehicleId);
                initializePersonAndPlanIfNeeded(personId);

                // add previous activity and leg to plan
                Person person = jdeqsimPopulation.getPersons().get(personId);
                Plan plan = person.getSelectedPlan();
                Leg leg = createLeg(CAR, pte.linkIdsJava(), departureTime);

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

    private Leg createLeg(String mode, List<Object> links, double departureTime) {
        List<Id<Link>> linkIds = new ArrayList<>();

        for (Object linkObjId : links) {
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

    private static long linkWayId(Link link) {
        return Long.parseLong(link.getAttributes().getAttribute("origid").toString());
    }
}