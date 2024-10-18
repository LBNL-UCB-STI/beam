package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.PickUpDropOffHolder;
import beam.physsim.conditions.DoubleParking;
import beam.physsim.jdeqsim.cacc.CACCSettings;
import beam.sim.config.BeamConfig;
import beam.utils.metrics.TemporalEventCounter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import javax.inject.Inject;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JDEQSimulation extends org.matsim.core.mobsim.jdeqsim.JDEQSimulation {
    private final static Logger log = LoggerFactory.getLogger(JDEQSimulation.class);

    private final DoubleParking.CapacityReductionFunction capacityReductionFunction;
    private final TemporalEventCounter<Id<Link>> doubleParkingCounter;
    private final Option<CACCSettings> maybeCaccSettings;
    private final BeamConfig beamConfig;

    @Inject
    public JDEQSimulation(final JDEQSimConfigGroup config, final BeamConfig beamConfig,
                          final Scenario scenario, final EventsManager events,
                          final DoubleParking.CapacityReductionFunction capacityReductionFunction,
                          final TemporalEventCounter<Id<Link>> doubleParkingCounter,
                          Option<CACCSettings> maybeCaccSettings,
                          Option<PickUpDropOffHolder> maybePickUpDropOffHolder) {
        super(config, scenario, events);
        this.beamConfig = beamConfig;
        this.capacityReductionFunction = capacityReductionFunction;
        this.doubleParkingCounter = doubleParkingCounter;
        this.maybeCaccSettings = maybeCaccSettings;

        if (maybeCaccSettings.nonEmpty()) {
            Road.setRoadCapacityAdjustmentFunction(maybeCaccSettings.get().roadCapacityAdjustmentFunction());
        }

        if (maybePickUpDropOffHolder.nonEmpty()) {
            Road.setAdditionalLinkTravelTimeCalculationFunction(maybePickUpDropOffHolder.get().additionalLinkTravelTimeCalculationFunction());
        }
    }

    @Override
    protected void initializeVehicles() {
        if (maybeCaccSettings.nonEmpty()) {
            List<String> vehicleNotFound = new ArrayList<>();
            Map<String, Boolean> isCACCVehicle = maybeCaccSettings.get().isCACCVehicle();

            for (Person person : this.scenario.getPopulation().getPersons().values()) {
                Boolean isCaccEnabled = Boolean.FALSE;

                String personId = person.getId().toString();
                Boolean value = isCACCVehicle.get(personId);
                if (value == null) {
                    vehicleNotFound.add(personId);
                } else {
                    isCaccEnabled = value;
                }
                // the vehicle registers itself to the scheduler
                new Vehicle(getScheduler(), person, activityDurationInterpretation, isCaccEnabled, allRoads,
                        messageFactory, doubleParkingCounter);
            }

            logInitializeVehiclesOutcome(vehicleNotFound, isCACCVehicle);
        } else {
            for (Person person : this.scenario.getPopulation().getPersons().values()) {
                // the vehicle registers itself to the scheduler
                new Vehicle(getScheduler(), person, activityDurationInterpretation, false, allRoads, messageFactory,
                        doubleParkingCounter);
            }
        }
    }

    private void logInitializeVehiclesOutcome(List<String> vehicleNotFound, Map<String, Boolean> isCACCVehicle) {
        if (log.isInfoEnabled()) {
            int caccEnabledSize = (int) isCACCVehicle.entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .count();
            int populationPersonsSize = scenario.getPopulation().getPersons().values().size();
            String message = MessageFormat.format("isCACCVehicle map -> total vehicles {0}, not found {1}, CACC enabled {2}", populationPersonsSize, vehicleNotFound.size(), caccEnabledSize);
            log.info(message);
        }
    }

    @Override
    protected void initializeRoads() {
        Scheduler scheduler = getScheduler();
        allRoads.clear();
        double minimumRoadSpeedInMetersPerSecond = beamConfig.beam().physsim().minCarSpeedInMetersPerSecond();
        if (maybeCaccSettings.nonEmpty()) {
            CACCSettings caccSettings = maybeCaccSettings.get();
            for (Link link : scenario.getNetwork().getLinks().values()) {
                allRoads.put(link.getId(), new Road(scheduler, link,
                        caccSettings.speedAdjustmentFactor(),
                        minimumRoadSpeedInMetersPerSecond,
                        getConfig(), allRoads, capacityReductionFunction, doubleParkingCounter));
            }
        } else {
            for (Link link : scenario.getNetwork().getLinks().values()) {
                allRoads.put(link.getId(), new Road(scheduler, link,
                        1.0,
                        minimumRoadSpeedInMetersPerSecond,
                        getConfig(), allRoads, capacityReductionFunction, doubleParkingCounter));
            }
        }
    }

    @Override
    public void run() {
        super.run();
        if (maybeCaccSettings.nonEmpty()) {
            maybeCaccSettings.get().roadCapacityAdjustmentFunction().printStats();
        }
    }

}
