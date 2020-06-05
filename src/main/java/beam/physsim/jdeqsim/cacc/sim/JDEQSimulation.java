package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.CACCSettings;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.jdeqsim.*;

import javax.inject.Inject;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class JDEQSimulation extends org.matsim.core.mobsim.jdeqsim.JDEQSimulation {

    private final static Logger log = Logger.getLogger(JDEQSimulation.class);

    private final CACCSettings caccSettings;
    private final double speedAdjustmentFactor;
    private final double adjustedMinimumRoadSpeedInMetersPerSecond;

    @Inject
    public JDEQSimulation(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, CACCSettings caccSettings, double speedAdjustmentFactor, double adjustedMinimumRoadSpeedInMetersPerSecond) {
        super(config, scenario, events);
        this.caccSettings = caccSettings;
        this.speedAdjustmentFactor = speedAdjustmentFactor;
        this.adjustedMinimumRoadSpeedInMetersPerSecond = adjustedMinimumRoadSpeedInMetersPerSecond;
        Road.setRoadCapacityAdjustmentFunction(caccSettings.roadCapacityAdjustmentFunction());
    }

    @Override
    protected void initializeVehicles() {
        List<String> vehicleNotFound = new ArrayList<>();
        Map<String, Boolean> isCACCVehicle = caccSettings.isCACCVehicle();

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
            new Vehicle(getScheduler(), person, activityDurationInterpretation, isCaccEnabled);
        }

        logInitializeVehiclesOutcome(vehicleNotFound, isCACCVehicle);
    }

    private void logInitializeVehiclesOutcome(List<String> vehicleNotFound, Map<String, Boolean> isCACCVehicle) {
        if (log.isInfoEnabled()) {
            int caccEnabledSize = isCACCVehicle.entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                    .size();
            int populationPersonsSize = scenario.getPopulation().getPersons().values().size();
            String message = MessageFormat.format("isCACCVehicle map -> total vehicles {0}, not found {1}, CACC enabled {2}", populationPersonsSize, vehicleNotFound.size(), caccEnabledSize);
            log.info(message);
        }
    }

    @Override
    protected void initializeRoads() {
        Scheduler scheduler = getScheduler();
        HashMap<Id<Link>, org.matsim.core.mobsim.jdeqsim.Road> allRoads = new HashMap<>();
        for (Link link : scenario.getNetwork().getLinks().values()) {
            allRoads.put(link.getId(), new Road(scheduler, link, speedAdjustmentFactor, adjustedMinimumRoadSpeedInMetersPerSecond));
        }
        Road.setAllRoads(allRoads);
    }

    @Override
    public void run() {
        super.run();
        caccSettings.roadCapacityAdjustmentFunction().printStats();
    }

}
