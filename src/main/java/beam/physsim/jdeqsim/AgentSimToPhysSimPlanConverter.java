package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.BeamPersonDepartureEvent;
import beam.agentsim.events.LeavingParkingEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.agentsim.infrastructure.parking.ParkingType;
import beam.analysis.IterationStatsProvider;
import beam.physsim.PickUpDropOffCollector;
import beam.analysis.physsim.PhyssimCalcLinkSpeedDistributionStats;
import beam.analysis.physsim.PhyssimCalcLinkSpeedStats;
import beam.analysis.physsim.PhyssimNetworkComparisonEuclideanVsLengthAttribute;
import beam.analysis.physsim.PhyssimNetworkLinkLengthDistribution;
import beam.calibration.impl.example.CountsObjectiveFunction;
import beam.physsim.analysis.LinkStatsWithVehicleCategory;
import beam.physsim.cchRoutingAssignment.OsmInfoHolder;
import beam.physsim.cchRoutingAssignment.RoutingFrameworkTravelTimeCalculator;
import beam.physsim.cchRoutingAssignment.RoutingFrameworkWrapperImpl;
import beam.router.BeamRouter;
import beam.router.FreeFlowTravelTime;
import beam.sim.BeamConfigChangesObservable;
import beam.sim.BeamConfigChangesObserver;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.MetricsSupport;
import beam.sim.population.AttributesOfIndividual;
import beam.sim.population.PopulationAdjustment;
import beam.sim.population.PopulationAdjustment$;
import beam.utils.*;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.math.DoubleMath;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.core.utils.misc.Time;
import org.matsim.households.Household;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author asif and rwaraich.
 */
public class AgentSimToPhysSimPlanConverter implements BasicEventHandler, MetricsSupport, IterationStatsProvider, BeamConfigChangesObserver {

    public static final String CAR = "car";
    public static final String BUS = "bus";
    private static final String DUMMY_ACTIVITY = "DummyActivity";
    public static final double TOLERANCE = 0.00001;
    private final PhyssimCalcLinkSpeedStats linkSpeedStatsGraph;
    private final PhyssimCalcLinkSpeedDistributionStats linkSpeedDistributionStatsGraph;
    private final PhyssimNetworkLinkLengthDistribution physsimNetworkLinkLengthDistribution;
    private final PhyssimNetworkComparisonEuclideanVsLengthAttribute physsimNetworkEuclideanVsLengthAttribute;
    private final ActorRef router;
    private final OutputDirectoryHierarchy controlerIO;
    private final Logger log = LoggerFactory.getLogger(AgentSimToPhysSimPlanConverter.class);
    private final Scenario agentSimScenario;
    private final Option<PickUpDropOffCollector> pickUpDropOffCollector;
    private Population jdeqsimPopulation;
    private TravelTime aggregatedTravelTime;
    private final BeamServices beamServices;
    private final BeamConfigChangesObservable beamConfigChangesObservable;

    private AgentSimPhysSimInterfaceDebugger agentSimPhysSimInterfaceDebugger;

    // suppliers are used for sake of laziness
    private final Supplier<RoutingFrameworkTravelTimeCalculator> routingFrameworkTravelTimeCalculator;

    private BeamConfig beamConfig;
    private final Random rand = MatsimRandom.getRandom();
    private final boolean agentSimPhysSimInterfaceDebuggerEnabled;

    final Map<String, Boolean> caccVehiclesMap = new TreeMap<>();
    private final Map<Integer, Mean> binSpeed = new HashMap<>();

    private TravelTime prevTravelTime = new FreeFlowTravelTime();

    private final Random rnd;

    private Map<Id<Person>, Household> personToHouseHold;

    private final List<PathTraversalEvent> traversalEventsForPhysSimulation = new LinkedList<>();
    private final Map<String, String> driverToCurrentTripToken = new HashMap<>();
    private final Map<String, Long> driverToSyntheticTripCounter = new HashMap<>();

    private static final String ATTRIBUTE_PAYLOAD_IDS = "payloads";
    private static final String ATTRIBUTE_WEIGHT = "weight";
    private static final String ATTRIBUTE_DRIVER_ID = "driver_id";
    private static final String ATTRIBUTE_REROUTED_BY_MULTI_JDEQ_SIM = "rerouted_by_multi_jdeqsim";
    private static final String ATTRIBUTE_TRIP_ID_UNDERSCORE = "trip_id";
    private static final String ATTRIBUTE_TRIP_ID_CAMEL = "tripId";
    private static final double ROUTE_SYNC_DEPARTURE_TIME_TOLERANCE_SEC = 180.0;
    private static final int MAX_ROUTE_SYNC_DETAILS_AT_DEBUG = 500;

    public AgentSimToPhysSimPlanConverter(EventsManager eventsManager,
                                          TransportNetwork transportNetwork,
                                          OutputDirectoryHierarchy controlerIO,
                                          Scenario scenario,
                                          BeamServices beamServices,
                                          BeamConfigChangesObservable beamConfigChangesObservable,
                                          Option<PickUpDropOffCollector> pickUpDropOffCollector) {
        eventsManager.addHandler(this);
        this.beamServices = beamServices;
        this.controlerIO = controlerIO;
        this.router = beamServices.beamRouter();
        this.beamConfig = beamServices.beamConfig();
        this.rand.setSeed(beamConfig.matsim().modules().global().randomSeed());
        this.beamConfigChangesObservable = beamConfigChangesObservable;
        this.pickUpDropOffCollector = pickUpDropOffCollector;
        agentSimScenario = scenario;
        agentSimPhysSimInterfaceDebuggerEnabled = beamConfig.beam().physsim().jdeqsim().agentSimPhysSimInterfaceDebugger().enabled();

        if (agentSimPhysSimInterfaceDebuggerEnabled) {
            log.warn("AgentSimPhysSimInterfaceDebugger is enabled");
            agentSimPhysSimInterfaceDebugger = new AgentSimPhysSimInterfaceDebugger(beamServices.geo(), transportNetwork);
        }
        preparePhysSimForNewIteration();


        linkSpeedStatsGraph = new PhyssimCalcLinkSpeedStats(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        linkSpeedDistributionStatsGraph = new PhyssimCalcLinkSpeedDistributionStats(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        physsimNetworkLinkLengthDistribution = new PhyssimNetworkLinkLengthDistribution(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        physsimNetworkEuclideanVsLengthAttribute = new PhyssimNetworkComparisonEuclideanVsLengthAttribute(agentSimScenario.getNetwork(), controlerIO, beamConfig);
        beamConfigChangesObservable.addObserver(this);
        rnd = new Random(beamConfig.matsim().modules().global().randomSeed());

        routingFrameworkTravelTimeCalculator = Suppliers.memoize(() -> new RoutingFrameworkTravelTimeCalculator(
                beamServices,
                new OsmInfoHolder(beamServices),
                new RoutingFrameworkWrapperImpl(beamServices)
        ))::get;
    }


    private void preparePhysSimForNewIteration() {
        jdeqsimPopulation = PopulationUtils.createPopulation(agentSimScenario.getConfig());
        buildPersonToHousehold();
        driverToCurrentTripToken.clear();
        driverToSyntheticTripCounter.clear();
    }

    public void buildPersonToHousehold() {
        personToHouseHold = beamServices.matsimServices().getScenario().getHouseholds().getHouseholds().values().stream().flatMap(h -> h.getMemberIds().stream().map(m -> new AbstractMap.SimpleEntry<Id<Person>, Household>(m, h)))
                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
    }

    private void setupActorsAndRunPhysSim(IterationEndsEvent iterationEndsEvent) {
        // I don't use single class `UpdateTravelTime` here and make decision in `BeamRouter` because
        // below we have `linkStatsGraph.notifyIterationEnds` call which internally will call `BeamCalcLinkStats.addData`
        // which may change an internal state of travel time calculator (and it happens concurrently in CompletableFuture)
        //################################################################################################################
        Collection<? extends Link> links = agentSimScenario.getNetwork().getLinks().values();
        int maxHour = (int) TimeUnit.SECONDS.toHours(agentSimScenario.getConfig().travelTimeCalculator().getMaxTime()) + 1;

        int iterationNumber = iterationEndsEvent.getIteration();

        Map<String, double[]> travelTimeMap;
        TravelTime travelTimeFromPhysSim;
        VolumesAnalyzer volumesAnalyzer;

        String physSimName = beamConfig.beam().physsim().name();

        switch (physSimName) {
            case "JDEQSim":
            case "BPRSim":
            case "PARBPRSim":
                log.info("{} started", physSimName);

                RelaxationExperiment sim = RelaxationExperiment$.MODULE$.apply(beamConfig, agentSimScenario, jdeqsimPopulation,
                        beamServices, controlerIO, caccVehiclesMap, beamConfigChangesObservable, iterationNumber, rnd, pickUpDropOffCollector);
                log.info("RelaxationExperiment is {}, type is {}", sim.getClass().getSimpleName(), beamConfig.beam().physsim().relaxation().type());
                SimulationResult result = sim.run(prevTravelTime);
                travelTimeFromPhysSim = result.travelTime();
                volumesAnalyzer = result.volumesAnalyzer().getOrElse(this::dummyVolumesAnalyzer);
                // Safe travel time to reuse it on the next PhysSim iteration
                prevTravelTime = travelTimeFromPhysSim;

                travelTimeMap = TravelTimeCalculatorHelper.GetLinkIdToTravelTimeArray(links,
                        travelTimeFromPhysSim, maxHour);

                if (beamConfig.beam().debug().debugEnabled()) {
                    log.info(DebugLib.getMemoryLogMessage("Memory Use After Phys Sim: "));
                }

                log.info("{} End", physSimName);
                break;
            case "CCHRoutingAssignment":
                travelTimeMap = routingFrameworkTravelTimeCalculator.get().generateLink2TravelTimes(traversalEventsForPhysSimulation, iterationNumber, links, maxHour);
                travelTimeFromPhysSim = TravelTimeCalculatorHelper.CreateTravelTimeCalculator(beamConfig.beam().agentsim().timeBinSize(), travelTimeMap);
                volumesAnalyzer = dummyVolumesAnalyzer();
                log.warn("For CCHRoutingAssignment physsim the iteration x.linkstats.csv.gz is going to contain wrong" +
                        " volumes (1.0 for all the entries)");
                break;
            default:
                throw new RuntimeException(String.format("Unknown physsim type: %s", physSimName));
        }

        String objectiveFunction = beamConfig.beam().calibration().objectiveFunction();
        if (this.controlerIO != null
                && objectiveFunction.toLowerCase().contains("counts")) {
            String inputCountsFile = beamConfig.matsim().modules().counts().inputCountsFile();
            boolean countsConfigured = inputCountsFile != null
                    && !inputCountsFile.trim().isEmpty()
                    && !"null".equalsIgnoreCase(inputCountsFile.trim());

            if (!countsConfigured) {
                log.warn("Skipping counts objective evaluation because matsim.modules.counts.inputCountsFile is not configured.");
            } else {
                String outPath =
                        controlerIO
                                .getIterationFilename(iterationNumber, "countsCompare.txt");
                Path countsComparePath = Path.of(outPath);
                if (!Files.exists(countsComparePath)) {
                    log.warn("Skipping counts objective evaluation because {} was not created.", countsComparePath);
                } else {
                    try {
                        double countsError = CountsObjectiveFunction.evaluateFromRun(outPath);
                        log.info("counts Error: " + countsError);
                    } catch (Exception e) {
                        log.error("Failed to evaluate counts objective from {}", countsComparePath, e);
                    }
                }
            }
        }

        TravelTime freeFlow = new FreeFlowTravelTime();
        int nBins = 0;
        int nBinsWithUnexpectedlyLowSpeed = 0;
        for (Map.Entry<String, double[]> entry : travelTimeMap.entrySet()) {
            int hour = 0;
            Link link = agentSimScenario.getNetwork().getLinks().get(Id.createLinkId(entry.getKey()));
            for (double linkTravelTime : entry.getValue()) {
                double speed = link.getLength() / linkTravelTime;
                if (speed < beamConfig.beam().physsim().minCarSpeedInMetersPerSecond()) {
                    double linkTravelTime1 = travelTimeFromPhysSim.getLinkTravelTime(link, hour * 60.0 * 60.0, null, null);
                    double freeFlowTravelTime = freeFlow.getLinkTravelTime(link, hour * 60.0 * 60.0, null, null);
                    log.debug("{} {} {}", linkTravelTime, linkTravelTime1, freeFlowTravelTime);
                    nBinsWithUnexpectedlyLowSpeed++;
                }
                hour++;
                nBins++;
            }
        }
        if (nBinsWithUnexpectedlyLowSpeed > 0) {
            log.error("Iteration {} had {} link speed bins (of {}) with speed smaller than {}.", iterationNumber, nBinsWithUnexpectedlyLowSpeed, nBins, beamConfig.beam().physsim().minCarSpeedInMetersPerSecond());
        }

        TravelTime travelTimeForR5 = travelTimeFromPhysSim;
        int startingIterationForTravelTimesMSA = beamConfig.beam().routing().startingIterationForTravelTimesMSA();
        if (startingIterationForTravelTimesMSA <= iterationNumber) {
            travelTimeMap = processTravelTime(links, travelTimeMap, maxHour);
            travelTimeForR5 = aggregatedTravelTime;
        }

        int lastIteration = beamConfig.matsim().modules().controler().lastIteration();
        // We write travel time map on 0-th iteration or (iterationNumber + 1) % writeEventsInterval because this travel time will be used in the next iteration
        // It's needed to be in sync with `RouteDumper` and allow us to reproduce routes calculation
        if ((iterationNumber == lastIteration) || beamConfig.beam().outputs().writeEventsInterval() > 0 &&
                iterationNumber % beamConfig.beam().outputs().writeEventsInterval() == 0) {
            String filePath = beamServices.matsimServices().getControlerIO().getIterationFilename(iterationNumber, "travel_time_map.bin");
            try {
                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath))) {
                    oos.writeObject(travelTimeMap);
                }
            } catch (Exception ex) {
                log.error("Can't write travel time map", ex);
            }
        }

        router.tell(new BeamRouter.TryToSerialize(travelTimeMap), ActorRef.noSender());
        router.tell(new BeamRouter.UpdateTravelTimeRemote(travelTimeMap), ActorRef.noSender());
        //################################################################################################################

        writeTravelTime(travelTimeForR5, volumesAnalyzer, iterationEndsEvent);

        router.tell(new BeamRouter.UpdateTravelTimeLocal(travelTimeForR5), ActorRef.noSender());

        List<CompletableFuture<Void>> completableFutures = new ArrayList<>();
        completableFutures.add(CompletableFuture.runAsync(() -> linkSpeedStatsGraph.notifyIterationEnds(iterationNumber, travelTimeFromPhysSim)));

        completableFutures.add(CompletableFuture.runAsync(() -> linkSpeedDistributionStatsGraph.notifyIterationEnds(iterationNumber, travelTimeFromPhysSim)));

        completableFutures.add(CompletableFuture.runAsync(() -> physsimNetworkLinkLengthDistribution.notifyIterationEnds(iterationNumber)));

        completableFutures.add(CompletableFuture.runAsync(() -> physsimNetworkEuclideanVsLengthAttribute.notifyIterationEnds(iterationNumber)));

        writeIterationCsv(iterationNumber);

        if (iterationNumber == lastIteration) {
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

    private VolumesAnalyzer dummyVolumesAnalyzer() {
        return new VolumesAnalyzer(3600, 120 * 3600, agentSimScenario.getNetwork()) {
            final double[] dummyVolumeArray = IntStream.range(0, 121).mapToDouble(x -> 1.0).toArray();

            @Override
            public double[] getVolumesPerHourForLink(Id<Link> linkId) {
                return dummyVolumeArray;
            }
        };
    }

    private void writeTravelTime(TravelTime travelTimeForR5, VolumesAnalyzer volumesAnalyzer, IterationEndsEvent iterationEndsEvent) {
        TravelTimeCalculatorConfigGroup cfg = new TravelTimeCalculatorConfigGroup();
        int endTimeInSeconds = (int) Time.parseTime(beamConfig.beam().agentsim().endTime());
        cfg.setMaxTime(endTimeInSeconds);
        Network network = agentSimScenario.getNetwork();
        LinkStatsWithVehicleCategory linkStats = new LinkStatsWithVehicleCategory(network, cfg);
        String filePath = controlerIO.getIterationFilename(
                iterationEndsEvent.getIteration(),
                String.format("linkstats.%s", linkStatsOutputFileType())
        );
        linkStats.writeLinkStatsWithTruckVolumes(volumesAnalyzer, travelTimeForR5, filePath);
    }

    private String linkStatsOutputFileType() {
        String fileType = beamConfig.beam().physsim().linkStatsOutputFileType();
        if (fileType == null) return "csv.gz";
        fileType = fileType.trim();
        if (fileType.isEmpty()) return "csv.gz";
        if (fileType.startsWith(".")) fileType = fileType.substring(1);
        return fileType.toLowerCase();
    }

    private boolean shouldWritePlans(int iterationNumber) {
        return shouldWriteInIteration(iterationNumber, beamConfig.beam().physsim().writePlansInterval());
    }

    private boolean shouldWriteInIteration(int iterationNumber, int interval) {
        return interval == 1 || (interval > 0 && iterationNumber >= interval && iterationNumber % interval == 0);
    }

    private void writePhyssimPlans(IterationEndsEvent event) {
        if (shouldWritePlans(event.getIteration())) {
            final String plansFilename = controlerIO.getIterationFilename(event.getIteration(), "physsimPlans.xml.gz");
            CompletableFuture.runAsync(() -> new PopulationWriter(jdeqsimPopulation).write(plansFilename));
        }
    }

    public static boolean isPhyssimMode(String mode) {
        return mode.equalsIgnoreCase(CAR) || mode.equalsIgnoreCase(BUS);
    }

    private boolean isCarMode(String mode) {
        return mode.equalsIgnoreCase(CAR);
    }

    @Override
    public void handleEvent(Event event) {
        if (agentSimPhysSimInterfaceDebuggerEnabled) {
            agentSimPhysSimInterfaceDebugger.handleEvent(event);
        }

        if (event instanceof BeamPersonDepartureEvent || BeamPersonDepartureEvent.EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            handleBeamPersonDepartureEvent(event);
        } else if (event instanceof PathTraversalEvent) {
            handlePathTraversalEvent((PathTraversalEvent) event);
        } else if (event instanceof LeavingParkingEvent) {
            handleLeavingParkingEvent((LeavingParkingEvent) event);
        }
    }

    private void handleBeamPersonDepartureEvent(Event event) {
        String driverId;
        String tripToken;

        if (event instanceof BeamPersonDepartureEvent) {
            BeamPersonDepartureEvent beamEvent = (BeamPersonDepartureEvent) event;
            driverId = normalizeTripId(beamEvent.getPersonId().toString());
            tripToken = normalizeTripId(beamEvent.getTripId());
        } else {
            Map<String, String> attributes = event.getAttributes();
            driverId = normalizeTripId(attributes.get(BeamPersonDepartureEvent.ATTRIBUTE_PERSON));
            if (driverId == null) {
                driverId = normalizeTripId(attributes.get("person"));
            }
            tripToken = normalizeTripId(attributes.get(BeamPersonDepartureEvent.ATTRIBUTE_TRIP_ID));
            if (tripToken == null) {
                tripToken = normalizeTripId(attributes.get("tripId"));
            }
        }

        if (driverId == null) {
            return;
        }

        if (tripToken == null) {
            long next = driverToSyntheticTripCounter.merge(driverId, 1L, Long::sum);
            tripToken = "depSeq:" + next;
        }
        driverToCurrentTripToken.put(driverId, tripToken);
    }

    private void handleLeavingParkingEvent(LeavingParkingEvent event) {
        if (event.parkingType() != ParkingType.DoubleParking$.MODULE$) {
            return;
        }

        String vehicleId = event.vehicleId().toString();
        final Person person = jdeqsimPopulation.getPersons().get(Id.createPersonId(vehicleId));
        if (person == null) {
            return;
        }
        Leg lastLeg = (Leg) Iterables.getLast(person.getSelectedPlan().getPlanElements());
        boolean zeroTimeParking = DoubleMath.fuzzyEquals(
                (Double) lastLeg.getAttributes().getAttribute("event_time"), event.time(), TOLERANCE);
        if (zeroTimeParking) {
            return;
        }
        lastLeg.getAttributes().putAttribute("ended_with_double_parking", true);
    }

    private void handlePathTraversalEvent(PathTraversalEvent pte) {
        String mode = pte.mode().value();

        if (isCarMode(mode)) {
            double departureTime = pte.departureTime();
            double travelTime = pte.arrivalTime() - departureTime;

            if (travelTime > 0.0) {
                double speed = pte.legLength() / travelTime;
                int bin = (int) departureTime / beamConfig.beam().physsim().linkStatsBinSize();
                Mean mean = binSpeed.getOrDefault(bin, new Mean());
                mean.increment(speed);
            }
        }
        // pt sampling
        // TODO: if requested, add beam.physsim.ptSamplingMode (pathTraversal | busLine), which controls if instead of filtering outWriter
        // pathTraversal, a busLine should be filtered out, avoiding jumping buses in visualization (but making traffic flows less precise).

        if (mode.equalsIgnoreCase(BUS) && rand.nextDouble() > beamConfig.beam().physsim().ptSampleSize()) {
            return;
        }
        if (isPhyssimMode(mode)) {
            traversalEventsForPhysSimulation.add(pte);

            String driverId = pte.driverId();
            String vehicleId = pte.vehicleId().toString();

            String vehicleType = pte.vehicleType();
            Id<BeamVehicleType> beamVehicleTypeId = Id.create(vehicleType, BeamVehicleType.class);
            boolean isCaccEnabled = beamServices.beamScenario().vehicleTypes().get(beamVehicleTypeId).get().isCaccEnabled();
            caccVehiclesMap.put(vehicleId, isCaccEnabled);

            addPTEtoPhysSimPlans(pte, vehicleId, driverId, 0);

            double fractionOfEvents = beamConfig.beam().physsim().duplicatePTE().fractionOfEventsToDuplicate();
            if (mode.equalsIgnoreCase(CAR)) {
                long numberOfDuplicates = MathUtils.roundUniformly(fractionOfEvents);
                for (int i = 0; i < numberOfDuplicates; i++) {
                    int departureTimeShift = getDepartureTimeShift();
                    String clonedVehicleId = vehicleId + "_clone" + i;
                    String clonedDriverId = driverId + "_clone" + i;
                    addPTEtoPhysSimPlans(pte, clonedVehicleId, clonedDriverId, departureTimeShift);
                }
            }
        }
    }

    private int getDepartureTimeShift() {
        int minShift = Math.min(beamConfig.beam().physsim().duplicatePTE().departureTimeShiftMin(),
                beamConfig.beam().physsim().duplicatePTE().departureTimeShiftMax());
        int maxShift = Math.max(beamConfig.beam().physsim().duplicatePTE().departureTimeShiftMin(),
                beamConfig.beam().physsim().duplicatePTE().departureTimeShiftMax());
        int shiftSize = Math.abs(maxShift - minShift);

        return minShift + rand.nextInt(shiftSize);
    }

    private void addPTEtoPhysSimPlans(PathTraversalEvent pte, String vehicleId, String driverId, Integer departureTimeShift) {
        // For every PathTraversalEvent which has PhysSim mode (CAR or BUS) we create
        // - If person does not exist, we create Person from `vehicleId`. For that person we create plan, set it to selected plan and add attributes from the original person
        // - Create leg
        // - Create dummy activity
        final Person person = initializePersonAndPlanIfNeeded(Id.createPersonId(vehicleId), Id.createPersonId(driverId));
        final Plan plan = person.getSelectedPlan();
        final Leg lastLeg = (Leg) Iterables.getLast(plan.getPlanElements(), null);
        // it means that this is the same leg that is split for parking
        final Leg connectedLeg = lastLeg != null && DoubleMath.fuzzyEquals(
                (Double) lastLeg.getAttributes().getAttribute("event_time"), pte.departureTime(), TOLERANCE) && (Objects.equals(lastLeg.getMode(), pte.mode().value()))
                ? lastLeg : null;
        final Leg leg = createLeg(pte, connectedLeg, departureTimeShift);
        if (driverId.startsWith("ft") && (leg != null)) {
            final String payloadIdString = Arrays.stream(pte.payloadIds()).map(Object::toString).collect(Collectors.joining(","));
            final String weightString = String.valueOf(pte.weight());
            leg.getAttributes().putAttribute(ATTRIBUTE_PAYLOAD_IDS, payloadIdString);
            leg.getAttributes().putAttribute(ATTRIBUTE_WEIGHT, weightString);
        }
        if (leg != null) {
            leg.getAttributes().putAttribute(ATTRIBUTE_DRIVER_ID, driverId);
            String tripToken = resolveTripTokenForLeg(driverId, connectedLeg);
            if (tripToken != null) {
                leg.getAttributes().putAttribute(ATTRIBUTE_TRIP_ID_UNDERSCORE, tripToken);
            }
        }

        if (leg == null) {
            return;
        }

        if (connectedLeg == null) {
            Activity previousActivity = jdeqsimPopulation.getFactory().createActivityFromLinkId(DUMMY_ACTIVITY, leg.getRoute().getStartLinkId());
            Double departureTime = getLegAttributeAsDouble(leg, "departure_time");
            previousActivity.setEndTime(departureTime == null ? pte.departureTime() : departureTime);
            plan.addActivity(previousActivity);
            plan.addLeg(leg);
        } else {
            plan.getPlanElements().set(plan.getPlanElements().size() - 1, leg);
        }
    }

    private void writeIterationCsv(int iteration) {
        String path = controlerIO.getIterationFilename(iteration, "agentSimAverageSpeed.csv");

        List<String> rows = binSpeed.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> (entry.getKey() + 1) + "," + entry.getValue().getResult())
                .collect(Collectors.toList());

        FileUtils.writeToFile(path, Option.apply("timeBin,averageSpeed"), StringUtils.join(rows, "\n"), Option.empty());
        binSpeed.clear();
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
            jdeqsimPopulation.addPerson(person);
            final Person originalPerson = agentSimScenario.getPopulation().getPersons().get(driverId);
            final Person personToCopyFrom = originalPerson == null ? agentSimScenario.getPopulation().getPersons().get(vehicleId) : originalPerson;
            // Try to copy person's attributes from original `agentSimScenario` to the created one. Attributes are important because they are used during R5 routing
            if (personToCopyFrom != null) {
                try {
                    Attributes attributes = personToCopyFrom.getAttributes();
                    Stream<String> keys = Arrays.stream(attributes.toString().split("\\{ key=")).filter(x -> x.contains(";")).map(z -> z.split(";")[0]);
                    keys.forEach(key -> person.getAttributes().putAttribute(key, attributes.getAttribute(key)));
                    final Household hh = personToHouseHold.get(personToCopyFrom.getId());
                    final AttributesOfIndividual attributesOfIndividual = PopulationAdjustment$.MODULE$.createAttributesOfIndividual(beamServices.beamScenario(), personToCopyFrom, hh);
                    person.getCustomAttributes().put(PopulationAdjustment.BEAM_ATTRIBUTES(), attributesOfIndividual);
                } catch (Exception ex) {
                    log.error("Could not create attributes for person " + vehicleId, ex);
                }
            }
            return person;
        } else {
            return alreadyInitedPerson;
        }
    }

    /**
     * Creates a matsim Leg
     *
     * @param pte                PathTraversalEvent
     * @param connectedLeg       the previous leg that is directly connected to the current one (no activity/delay between them).
     *                           If it is provided then the previous leg is combined with the current PTE
     * @param departureTimeShift a time sift to
     * @return
     */
    private Leg createLeg(PathTraversalEvent pte, Leg connectedLeg, Integer departureTimeShift) {
        List<Id<Link>> linkIds = new ArrayList<>();

        if (connectedLeg != null) {
            NetworkRoute lastRoute = (NetworkRoute) connectedLeg.getRoute();
            linkIds.add(lastRoute.getStartLinkId());
            linkIds.addAll(lastRoute.getLinkIds());
            if (!lastRoute.getLinkIds().isEmpty() || lastRoute.getStartLinkId() != lastRoute.getEndLinkId()) {
                linkIds.add(lastRoute.getEndLinkId());
            }
        }

        List<Object> objects = pte.linkIdsJava();
        // most of the time the last link of previous leg is the first link of current leg - we are avoiding this
        boolean sameLinkAtTheEnd;

        try {
            sameLinkAtTheEnd = !linkIds.isEmpty()
                    && String.valueOf(pte.linkIds()[0]).equals(Iterables.getLast(linkIds).toString());
        } catch (java.util.NoSuchElementException e) {
            log.error("Mismatched path traversal in physsim plans: {}, matched leg: {}", pte, connectedLeg);
            return null;
        }
        for (int i = sameLinkAtTheEnd ? 1 : 0; i < objects.size(); i++) {
            Object linkObjId = objects.get(i);
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
        //Removing first and last link
        linkIds.removeAll(Lists.newArrayList(route.getStartLinkId(), route.getEndLinkId()));
        double length = linkIds.stream().mapToDouble(linkId -> networkLinks.get(linkId).getLength()).sum();
        route.setDistance(length);

        Leg leg = jdeqsimPopulation.getFactory().createLeg(CAR);
        Double connectedDepartureTime = connectedLeg == null ? null : getLegAttributeAsDouble(connectedLeg, "departure_time");
        if (connectedDepartureTime == null && connectedLeg != null) {
            connectedDepartureTime = optionalTimeToSeconds(connectedLeg.getDepartureTime());
        }
        double actualDepartureTime = connectedDepartureTime == null ? pte.departureTime() : connectedDepartureTime;
        double departureTime = Math.max(0.0, actualDepartureTime + departureTimeShift);
        double travelTime = pte.arrivalTime() - actualDepartureTime;
        leg.setDepartureTime(departureTime);
        leg.setTravelTime(0);
        leg.setRoute(route);
        leg.getAttributes().putAttribute("travel_time", travelTime);
        // math.max in case of negative departureTimeShift
        leg.getAttributes().putAttribute("departure_time", departureTime);
        leg.getAttributes().putAttribute("event_time", departureTime + travelTime);
        return leg;
    }

    public Population generatePopulation() {
        createLastActivityOfDayForPopulation();
        return jdeqsimPopulation;
    }

    public void startPhysSim(IterationEndsEvent iterationEndsEvent, TravelTime initialTravelTime) {
        aggregatedTravelTime = initialTravelTime;
        if (initialTravelTime != null) {
            prevTravelTime = initialTravelTime;
        }
        generatePopulation();
        writePhyssimPlans(iterationEndsEvent);
        long start = System.currentTimeMillis();
        setupActorsAndRunPhysSim(iterationEndsEvent);
        synchronizePhysSimRoutesToAgentSimPlans(iterationEndsEvent.getIteration());
        log.info("PhysSim for iteration {} took {} ms", iterationEndsEvent.getIteration(), System.currentTimeMillis() - start);
        preparePhysSimForNewIteration();
    }

    private void synchronizePhysSimRoutesToAgentSimPlans(int iterationNumber) {
        int synchronizedPeople = 0;
        int synchronizedLegs = 0;
        int consideredDrivers = 0;
        int skippedCloneMappings = 0;
        int skippedMissingPeople = 0;
        int skippedNoCompatibleLegs = 0;
        int skippedMissingPlans = 0;
        int perfectMatches = 0;
        int partialMatches = 0;
        int mismatchedCounts = 0;
        int matchedByTripId = 0;
        int matchedByTripIdWithEndLinkMismatch = 0;
        int detailedInfoLogs = 0;
        int suppressedDetailedInfoLogs = 0;
        final boolean routeSyncDebugEnabled = log.isDebugEnabled();

        Map<Id<Person>, List<Leg>> driverToPhysSimCarLegs = new HashMap<>();
        for (Person physSimPerson : jdeqsimPopulation.getPersons().values()) {
            Plan physSimPlan = physSimPerson.getSelectedPlan();
            if (physSimPlan == null) {
                continue;
            }
            for (Leg leg : getCarLegs(physSimPlan)) {
                Object driverIdRaw = leg.getAttributes().getAttribute(ATTRIBUTE_DRIVER_ID);
                if (driverIdRaw == null) {
                    continue;
                }
                if (!isTrue(leg.getAttributes().getAttribute(ATTRIBUTE_REROUTED_BY_MULTI_JDEQ_SIM))) {
                    continue;
                }
                Id<Person> driverId = Id.createPersonId(driverIdRaw.toString());
                if (driverId.toString().contains("_clone")) {
                    skippedCloneMappings++;
                    continue;
                }
                driverToPhysSimCarLegs.computeIfAbsent(driverId, id -> new ArrayList<>()).add(leg);
            }
        }

        for (Map.Entry<Id<Person>, List<Leg>> entry : driverToPhysSimCarLegs.entrySet()) {
            consideredDrivers++;
            Id<Person> driverId = entry.getKey();
            Person agentSimDriverPerson = agentSimScenario.getPopulation().getPersons().get(driverId);
            if (agentSimDriverPerson == null) {
                skippedMissingPeople++;
                continue;
            }

            Plan agentSimPlan = agentSimDriverPerson.getSelectedPlan();
            if (agentSimPlan == null) {
                skippedMissingPlans++;
                continue;
            }

            List<Leg> physSimCarLegs = sortLegsByDepartureTime(entry.getValue());
            List<Leg> agentSimCarLegs = sortLegsByDepartureTime(getCarLegs(agentSimPlan));
            if (physSimCarLegs.isEmpty() || agentSimCarLegs.isEmpty()) {
                continue;
            }

            if (physSimCarLegs.size() != agentSimCarLegs.size()) {
                mismatchedCounts++;
            }

            List<LegMatch> matches = matchLegsForSynchronization(physSimCarLegs, agentSimCarLegs);
            if (matches.isEmpty()) {
                skippedNoCompatibleLegs++;
                if (routeSyncDebugEnabled) {
                    if (detailedInfoLogs < MAX_ROUTE_SYNC_DETAILS_AT_DEBUG) {
                        log.debug(
                                "Skipping route synchronization for driver {} at iteration {} due to no compatible legs (physSimRerouted={}, agentSimCar={}). Sample physSim legs: {}. Sample agentSim legs: {}",
                                driverId,
                                iterationNumber,
                                physSimCarLegs.size(),
                                agentSimCarLegs.size(),
                                summarizeLegs(physSimCarLegs, 3),
                                summarizeLegs(agentSimCarLegs, 3)
                        );
                        detailedInfoLogs++;
                    } else {
                        suppressedDetailedInfoLogs++;
                    }
                }
                continue;
            }

            if (matches.size() == physSimCarLegs.size() && matches.size() == agentSimCarLegs.size()) {
                perfectMatches++;
            } else {
                partialMatches++;
                if (routeSyncDebugEnabled) {
                    if (detailedInfoLogs < MAX_ROUTE_SYNC_DETAILS_AT_DEBUG) {
                        log.debug(
                                "Route synchronization partially matched driver {} at iteration {} (physSimRerouted={}, agentSimCar={}, matched={}, unmatchedPhys={}, unmatchedAgent={}). Sample unmatched physSim legs: {}. Sample unmatched agentSim legs: {}",
                                driverId,
                                iterationNumber,
                                physSimCarLegs.size(),
                                agentSimCarLegs.size(),
                                matches.size(),
                                physSimCarLegs.size() - matches.size(),
                                agentSimCarLegs.size() - matches.size(),
                                summarizeUnmatchedLegs(physSimCarLegs, matches, true, 3),
                                summarizeUnmatchedLegs(agentSimCarLegs, matches, false, 3)
                        );
                        detailedInfoLogs++;
                    } else {
                        suppressedDetailedInfoLogs++;
                    }
                }
            }

            matchedByTripId += (int) matches.stream().filter(match -> match.matchedByTripId).count();
            matchedByTripIdWithEndLinkMismatch += (int) matches.stream()
                    .filter(match -> match.matchedByTripId && !legEndLinksMatch(match.sourceLeg, match.targetLeg))
                    .count();

            int updatedLegsForPerson = 0;
            for (LegMatch match : matches) {
                if (copyLegRouteAndTiming(match.sourceLeg, match.targetLeg)) {
                    updatedLegsForPerson++;
                    synchronizedLegs++;
                }
            }
            if (updatedLegsForPerson > 0) {
                synchronizedPeople++;
            }
        }

        log.info(
                "PhysSim route synchronization at iteration {} considered {} drivers and updated {} car legs for {} persons. Matching summary: perfectMatches={}, partialMatches={}, countMismatches={}, matchedByTripId={}, matchedByTripIdWithEndLinkMismatch={}, skippedNoCompatibleLegs={}. Other skips: cloneMappings={}, missingPeople={}, missingPlans={}. Detailed debug logs emitted={}, suppressed={}",
                iterationNumber,
                consideredDrivers,
                synchronizedLegs,
                synchronizedPeople,
                perfectMatches,
                partialMatches,
                mismatchedCounts,
                matchedByTripId,
                matchedByTripIdWithEndLinkMismatch,
                skippedNoCompatibleLegs,
                skippedCloneMappings,
                skippedMissingPeople,
                skippedMissingPlans,
                detailedInfoLogs,
                suppressedDetailedInfoLogs
        );
    }

    private List<LegMatch> matchLegsForSynchronization(List<Leg> sourceLegs, List<Leg> targetLegs) {
        if (sourceLegs.size() == targetLegs.size()) {
            List<LegMatch> orderedMatches = new ArrayList<>();
            for (int i = 0; i < sourceLegs.size(); i++) {
                Leg sourceLeg = sourceLegs.get(i);
                Leg targetLeg = targetLegs.get(i);
                if (legTripIdsConflict(sourceLeg, targetLeg)) {
                    continue;
                }
                orderedMatches.add(new LegMatch(sourceLeg, targetLeg, i, i, hasSameTripId(sourceLeg, targetLeg)));
            }
            return orderedMatches;
        }

        List<LegMatch> matches = new ArrayList<>();
        int targetSearchStart = 0;

        for (int sourceIdx = 0; sourceIdx < sourceLegs.size(); sourceIdx++) {
            Leg sourceLeg = sourceLegs.get(sourceIdx);
            int bestTargetIdx = -1;
            int bestTripIdPriority = Integer.MAX_VALUE;
            double bestDepartureScore = Double.MAX_VALUE;
            boolean bestMatchedByTripId = false;

            for (int targetIdx = targetSearchStart; targetIdx < targetLegs.size(); targetIdx++) {
                Leg targetLeg = targetLegs.get(targetIdx);
                if (!legsCompatibleForRouteSync(sourceLeg, targetLeg)) {
                    continue;
                }

                boolean sameTripId = hasSameTripId(sourceLeg, targetLeg);
                int tripIdPriority = sameTripId ? 0 : 1;
                double departureScore = departureTimeDifferenceOrMax(sourceLeg, targetLeg);

                if (tripIdPriority < bestTripIdPriority
                        || (tripIdPriority == bestTripIdPriority && departureScore < bestDepartureScore)) {
                    bestTripIdPriority = tripIdPriority;
                    bestDepartureScore = departureScore;
                    bestTargetIdx = targetIdx;
                    bestMatchedByTripId = sameTripId;
                }
            }

            if (bestTargetIdx >= 0) {
                matches.add(new LegMatch(sourceLeg, targetLegs.get(bestTargetIdx), sourceIdx, bestTargetIdx, bestMatchedByTripId));
                targetSearchStart = bestTargetIdx + 1;
            }
        }

        return matches;
    }

    private boolean legsCompatibleForRouteSync(Leg sourceLeg, Leg targetLeg) {
        if (legTripIdsConflict(sourceLeg, targetLeg)) {
            return false;
        }

        if (!(sourceLeg.getRoute() instanceof NetworkRoute) || !(targetLeg.getRoute() instanceof NetworkRoute)) {
            return false;
        }
        NetworkRoute sourceRoute = (NetworkRoute) sourceLeg.getRoute();
        NetworkRoute targetRoute = (NetworkRoute) targetLeg.getRoute();
        if (sourceRoute.getStartLinkId() == null || sourceRoute.getEndLinkId() == null) {
            return false;
        }
        if (targetRoute.getStartLinkId() == null || targetRoute.getEndLinkId() == null) {
            return false;
        }

        boolean sameTripId = hasSameTripId(sourceLeg, targetLeg);
        if (!Objects.equals(sourceRoute.getStartLinkId(), targetRoute.getStartLinkId())) {
            return false;
        }
        if (!sameTripId && !Objects.equals(sourceRoute.getEndLinkId(), targetRoute.getEndLinkId())) {
            return false;
        }

        return legsDepartureTimesCompatible(sourceLeg, targetLeg);
    }

    private boolean legsDepartureTimesCompatible(Leg sourceLeg, Leg targetLeg) {
        Double sourceDepartureTime = getLegDepartureTimeOrNull(sourceLeg);
        Double targetDepartureTime = getLegDepartureTimeOrNull(targetLeg);
        if (sourceDepartureTime == null || targetDepartureTime == null) {
            return true;
        }
        return Math.abs(sourceDepartureTime - targetDepartureTime) <= ROUTE_SYNC_DEPARTURE_TIME_TOLERANCE_SEC;
    }

    private boolean legEndLinksMatch(Leg sourceLeg, Leg targetLeg) {
        if (!(sourceLeg.getRoute() instanceof NetworkRoute) || !(targetLeg.getRoute() instanceof NetworkRoute)) {
            return false;
        }
        NetworkRoute sourceRoute = (NetworkRoute) sourceLeg.getRoute();
        NetworkRoute targetRoute = (NetworkRoute) targetLeg.getRoute();
        return Objects.equals(sourceRoute.getEndLinkId(), targetRoute.getEndLinkId());
    }

    private boolean legTripIdsConflict(Leg sourceLeg, Leg targetLeg) {
        String sourceTripId = getLegTripId(sourceLeg);
        String targetTripId = getLegTripId(targetLeg);
        return sourceTripId != null && targetTripId != null && !sourceTripId.equals(targetTripId);
    }

    private boolean hasSameTripId(Leg sourceLeg, Leg targetLeg) {
        String sourceTripId = getLegTripId(sourceLeg);
        String targetTripId = getLegTripId(targetLeg);
        return sourceTripId != null && sourceTripId.equals(targetTripId);
    }

    private double departureTimeDifferenceOrMax(Leg sourceLeg, Leg targetLeg) {
        Double sourceDepartureTime = getLegDepartureTimeOrNull(sourceLeg);
        Double targetDepartureTime = getLegDepartureTimeOrNull(targetLeg);
        if (sourceDepartureTime == null || targetDepartureTime == null) {
            return Double.MAX_VALUE;
        }
        return Math.abs(sourceDepartureTime - targetDepartureTime);
    }

    private String summarizeLegs(List<Leg> legs, int maxItems) {
        return legs.stream().limit(maxItems).map(this::describeLeg).collect(Collectors.joining("; "));
    }

    private String summarizeUnmatchedLegs(List<Leg> legs, List<LegMatch> matches, boolean source, int maxItems) {
        Set<Integer> matchedIndices = matches.stream()
                .map(match -> source ? match.sourceIndex : match.targetIndex)
                .collect(Collectors.toSet());
        return IntStream.range(0, legs.size())
                .filter(idx -> !matchedIndices.contains(idx))
                .mapToObj(legs::get)
                .limit(maxItems)
                .map(this::describeLeg)
                .collect(Collectors.joining("; "));
    }

    private String describeLeg(Leg leg) {
        StringBuilder builder = new StringBuilder();
        builder.append("dep=").append(formatTimeForLog(getLegDepartureTimeOrNull(leg)));
        builder.append(",travel=").append(formatTimeForLog(getLegTravelTimeOrNull(leg)));
        builder.append(",tripId=").append(Optional.ofNullable(getLegTripId(leg)).orElse("none"));
        if (leg.getRoute() instanceof NetworkRoute) {
            NetworkRoute route = (NetworkRoute) leg.getRoute();
            builder.append(",start=").append(route.getStartLinkId());
            builder.append(",end=").append(route.getEndLinkId());
            builder.append(",intermediate=").append(route.getLinkIds().size());
        } else {
            builder.append(",routeType=").append(leg.getRoute() == null ? "null" : leg.getRoute().getClass().getSimpleName());
        }
        builder.append(",rerouted=").append(isTrue(leg.getAttributes().getAttribute(ATTRIBUTE_REROUTED_BY_MULTI_JDEQ_SIM)));
        return builder.toString();
    }

    private String formatTimeForLog(Double value) {
        if (value == null || !isDefinedTime(value)) {
            return "undefined";
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private List<Leg> sortLegsByDepartureTime(List<Leg> legs) {
        List<Leg> sorted = new ArrayList<>(legs);
        sorted.sort(Comparator.comparingDouble(this::getLegDepartureTime));
        return sorted;
    }

    private double getLegDepartureTime(Leg leg) {
        Double departureTime = getLegDepartureTimeOrNull(leg);
        return departureTime != null && isDefinedTime(departureTime) ? departureTime : Double.MAX_VALUE;
    }

    private Double getLegDepartureTimeOrNull(Leg leg) {
        Double departureTime = getLegAttributeAsDouble(leg, "departure_time");
        if (departureTime == null) {
            departureTime = optionalTimeToSeconds(leg.getDepartureTime());
        }
        return departureTime != null && isDefinedTime(departureTime) ? departureTime : null;
    }

    private Double getLegTravelTimeOrNull(Leg leg) {
        Double travelTime = getLegAttributeAsDouble(leg, "travel_time");
        if (travelTime == null) {
            travelTime = optionalTimeToSeconds(leg.getTravelTime());
        }
        return travelTime != null && isDefinedTime(travelTime) ? travelTime : null;
    }

    private List<Leg> getCarLegs(Plan plan) {
        List<Leg> carLegs = new ArrayList<>();
        for (PlanElement element : plan.getPlanElements()) {
            if (element instanceof Leg) {
                Leg leg = (Leg) element;
                if (isCarMode(leg.getMode())) {
                    carLegs.add(leg);
                }
            }
        }
        return carLegs;
    }

    private boolean copyLegRouteAndTiming(Leg sourceLeg, Leg targetLeg) {
        if (!(sourceLeg.getRoute() instanceof NetworkRoute)) {
            return false;
        }
        NetworkRoute sourceRoute = (NetworkRoute) sourceLeg.getRoute();
        NetworkRoute copiedRoute = copyNetworkRoute(sourceRoute);
        if (copiedRoute == null) {
            return false;
        }

        targetLeg.setRoute(copiedRoute);
        targetLeg.setMode(sourceLeg.getMode());

        Double departureTime = getLegAttributeAsDouble(sourceLeg, "departure_time");
        if (departureTime == null) {
            departureTime = optionalTimeToSeconds(sourceLeg.getDepartureTime());
        }
        if (departureTime != null && isDefinedTime(departureTime)) {
            targetLeg.setDepartureTime(departureTime);
        } else {
            targetLeg.setDepartureTimeUndefined();
        }

        Double travelTime = getLegAttributeAsDouble(sourceLeg, "travel_time");
        if (travelTime == null) {
            travelTime = optionalTimeToSeconds(sourceLeg.getTravelTime());
        }
        if (travelTime != null && isDefinedTime(travelTime)) {
            targetLeg.setTravelTime(travelTime);
        } else {
            targetLeg.setTravelTimeUndefined();
        }

        copyLegAttribute(sourceLeg, targetLeg, "travel_time");
        copyLegAttribute(sourceLeg, targetLeg, "departure_time");
        copyLegAttribute(sourceLeg, targetLeg, "event_time");
        copyLegAttribute(sourceLeg, targetLeg, "ended_with_double_parking");
        copyLegAttribute(sourceLeg, targetLeg, ATTRIBUTE_PAYLOAD_IDS);
        copyLegAttribute(sourceLeg, targetLeg, ATTRIBUTE_WEIGHT);
        copyLegAttribute(sourceLeg, targetLeg, ATTRIBUTE_REROUTED_BY_MULTI_JDEQ_SIM);
        return true;
    }

    private NetworkRoute copyNetworkRoute(NetworkRoute sourceRoute) {
        if (sourceRoute.getStartLinkId() == null || sourceRoute.getEndLinkId() == null) {
            return null;
        }
        List<Id<Link>> routeLinks = new ArrayList<>();
        routeLinks.add(sourceRoute.getStartLinkId());
        routeLinks.addAll(sourceRoute.getLinkIds());
        routeLinks.add(sourceRoute.getEndLinkId());

        NetworkRoute copiedRoute = RouteUtils.createNetworkRoute(routeLinks, agentSimScenario.getNetwork());
        copiedRoute.setDistance(sourceRoute.getDistance());

        Double routeTravelTime = optionalTimeToSeconds(sourceRoute.getTravelTime());
        if (routeTravelTime != null && isDefinedTime(routeTravelTime)) {
            copiedRoute.setTravelTime(routeTravelTime);
        } else {
            copiedRoute.setTravelTimeUndefined();
        }
        return copiedRoute;
    }

    private Double getLegAttributeAsDouble(Leg leg, String attributeKey) {
        Object value = leg.getAttributes().getAttribute(attributeKey);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double optionalTimeToSeconds(OptionalTime optionalTime) {
        if (optionalTime == null) {
            return null;
        }
        try {
            return optionalTime.seconds();
        } catch (NoSuchElementException ignored) {
            return null;
        }
    }

    private boolean isDefinedTime(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private String getLegTripId(Leg leg) {
        Object underscore = leg.getAttributes().getAttribute(ATTRIBUTE_TRIP_ID_UNDERSCORE);
        String tripId = normalizeTripId(underscore);
        if (tripId != null) {
            return tripId;
        }
        Object camel = leg.getAttributes().getAttribute(ATTRIBUTE_TRIP_ID_CAMEL);
        return normalizeTripId(camel);
    }

    private String normalizeTripId(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String resolveTripTokenForLeg(String driverId, Leg connectedLeg) {
        if (connectedLeg != null) {
            String connectedLegTripId = getLegTripId(connectedLeg);
            if (connectedLegTripId != null) {
                return connectedLegTripId;
            }
        }
        return getCurrentTripTokenForDriver(driverId);
    }

    private String getCurrentTripTokenForDriver(String driverId) {
        String direct = normalizeTripId(driverToCurrentTripToken.get(driverId));
        if (direct != null) {
            return direct;
        }
        String baseDriverId = getBaseDriverId(driverId);
        if (!baseDriverId.equals(driverId)) {
            return normalizeTripId(driverToCurrentTripToken.get(baseDriverId));
        }
        return null;
    }

    private String getBaseDriverId(String driverId) {
        int cloneSuffixIndex = driverId.indexOf("_clone");
        if (cloneSuffixIndex > 0) {
            return driverId.substring(0, cloneSuffixIndex);
        }
        return driverId;
    }

    private void copyLegAttribute(Leg sourceLeg, Leg targetLeg, String attributeKey) {
        Object value = sourceLeg.getAttributes().getAttribute(attributeKey);
        if (value != null) {
            targetLeg.getAttributes().putAttribute(attributeKey, value);
        }
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static final class LegMatch {
        private final Leg sourceLeg;
        private final Leg targetLeg;
        private final int sourceIndex;
        private final int targetIndex;
        private final boolean matchedByTripId;

        private LegMatch(Leg sourceLeg, Leg targetLeg, int sourceIndex, int targetIndex, boolean matchedByTripId) {
            this.sourceLeg = sourceLeg;
            this.targetLeg = targetLeg;
            this.sourceIndex = sourceIndex;
            this.targetIndex = targetIndex;
            this.matchedByTripId = matchedByTripId;
        }
    }

    private void createLastActivityOfDayForPopulation() {
        for (Person p : jdeqsimPopulation.getPersons().values()) {
            Plan plan = p.getSelectedPlan();
            if (!plan.getPlanElements().isEmpty()) {
                PlanElement planElement = plan.getPlanElements().get(plan.getPlanElements().size() - 1);
                if (planElement instanceof Leg) {
                    Leg leg = (Leg) planElement;
                    plan.addActivity(jdeqsimPopulation.getFactory().createActivityFromLinkId(DUMMY_ACTIVITY, leg.getRoute().getEndLinkId()));
                }
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

        if (aggregatedTravelTime == null) {
            aggregatedTravelTime = currentTravelTime;
            return currentTravelTimeMap;
        } else {
            Map<String, double[]> map = TravelTimeCalculatorHelper.GetLinkIdToTravelTimeAvgArray(links, currentTravelTime, aggregatedTravelTime, maxHour);
            aggregatedTravelTime = TravelTimeCalculatorHelper.CreateTravelTimeCalculator(binSize, map);
            return map;
        }
    }

    @Override
    public void update(BeamConfigChangesObservable observable, BeamConfig updatedBeamConfig) {
        this.beamConfig = updatedBeamConfig;
    }
}
