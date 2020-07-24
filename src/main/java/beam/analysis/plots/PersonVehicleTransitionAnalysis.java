package beam.analysis.plots;

import beam.analysis.plot.PlotGraph;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.MetricsSupport;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.misc.Time;

import java.util.*;
import java.util.List;

public class PersonVehicleTransitionAnalysis implements GraphAnalysis, MetricsSupport {

    private static final List<String> vehicleType = new ArrayList<>(Arrays.asList("body", "rideHail", "others"));

    private Map<String, TreeMap<Integer, Integer>> personEnterCount = new HashMap<>();
    private Map<String, TreeMap<Integer, Integer>> personExitCount = new HashMap<>();
    private Map<String, TreeMap<Integer, Integer>> onRoutes = new HashMap<>();
    private final Map<String, Integer> modePerson = new HashMap<>();
    private static final String fileName = "tripHistogram";
    private static final String xAxisLabel = "time (binSize=<?> sec)";
    private final PlotGraph plotGraph = new PlotGraph();
    private final int binSize;
    private final int numOfBins;
    private final boolean writeGraph;
    private final OutputDirectoryHierarchy ioController;

    public PersonVehicleTransitionAnalysis(BeamConfig beamConfig, OutputDirectoryHierarchy ioController){
        binSize = beamConfig.beam().outputs().stats().binSize();
        String endTime = beamConfig.matsim().modules().qsim().endTime();
        Double _endTime = Time.parseTime(endTime);
        Double _numOfTimeBins = _endTime / binSize;
        _numOfTimeBins = Math.floor(_numOfTimeBins);
        numOfBins = _numOfTimeBins.intValue() + 1;
        this.writeGraph = beamConfig.beam().outputs().writeGraphs();
        this.ioController = ioController;
    }


    @Override
    public void processStats(Event event) {
        if (event instanceof PersonEntersVehicleEvent || event.getEventType().equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) ||
                event instanceof PersonLeavesVehicleEvent || event.getEventType().equalsIgnoreCase(PersonLeavesVehicleEvent.EVENT_TYPE))
            processPersonVehicleTransition(event);
    }

    @Override
    public void resetStats() {
        personExitCount = new HashMap<>();
        personEnterCount = new HashMap<>();
        onRoutes = new HashMap<>();
        modePerson.clear();
    }

    @Override
    public void createGraph(IterationEndsEvent event) {
        for (String mode : onRoutes.keySet()) {
            if (personEnterCount.size() == 0 && personExitCount.size() == 0) {
                continue;
            }
            if(writeGraph){
                plotGraph.writeGraphic(ioController, event.getIteration(), mode, fileName, personEnterCount, personExitCount, onRoutes, xAxisLabel, binSize);
            }
        }
    }

    private void processPersonVehicleTransition(Event event) {
        int index = plotGraph.getBinIndex(event.getTime(), this.binSize, this.numOfBins);
        if (event instanceof PersonEntersVehicleEvent && PersonEntersVehicleEvent.EVENT_TYPE.equals(event.getEventType())) {
            PersonEntersVehicleEvent peve = (PersonEntersVehicleEvent)event;
            String personId = peve.getPersonId().toString();
            if (personId.toLowerCase().contains("agent")) {
                return;
            }

            String vehicleId = peve.getVehicleId().toString();
            if (vehicleId.contains(":")) {
                String v = vehicleId.split(":")[0];
                if (!vehicleType.contains(v)) {
                    vehicleType.add(v);
                }
            }

            String unitVehicle;
            boolean isDigit = vehicleId.replace("-", "").chars().allMatch(Character::isDigit);
            if (isDigit) {
                unitVehicle = "car";
            } else {
                unitVehicle = vehicleType.stream().filter(vehicleId::contains).findAny().orElse("others");
            }


            Integer count = modePerson.get(unitVehicle);
            if (count == null) {
                count = 1;
            } else {
                count++;
            }
            modePerson.put(unitVehicle, count);

            TreeMap<Integer, Integer> indexCount = onRoutes.get(unitVehicle);
            if (indexCount == null) {
                indexCount = new TreeMap<>();
            }
            indexCount.put(index, count);
            onRoutes.put(unitVehicle, indexCount);

            TreeMap<Integer, Integer> personEnter = personEnterCount.get(unitVehicle);
            if (personEnter == null) {
                personEnter = new TreeMap<>();
                personEnter.put(index, 1);
            } else {
                Integer numOfPerson = personEnter.get(index);
                if (numOfPerson != null) {
                    numOfPerson++;
                } else {
                    numOfPerson = 1;
                }
                personEnter.put(index, numOfPerson);
            }
            personEnterCount.put(unitVehicle, personEnter);
        }


        if (event instanceof PersonLeavesVehicleEvent && PersonLeavesVehicleEvent.EVENT_TYPE.equals(event.getEventType())) {
            PersonLeavesVehicleEvent plve = (PersonLeavesVehicleEvent) event;
            String personId = plve.getPersonId().toString();
            String vehicleId = plve.getVehicleId().toString();

            if (personId.toLowerCase().contains("agent")) {
                return;
            }

            String unitVehicle;
            boolean isDigit = vehicleId.replace("-", "").chars().allMatch(Character::isDigit);
            if (isDigit) {
                unitVehicle = "car";
            } else {
                unitVehicle = vehicleType.stream().filter(vehicleId::contains).findAny().orElse("others");
            }

            Integer count = modePerson.get(unitVehicle);
            if (count != null) {
                count--;
            }
            modePerson.put(unitVehicle, count);

            TreeMap<Integer, Integer> indexCount = onRoutes.get(unitVehicle);
            if (indexCount != null) {
                indexCount.put(index, count);
            }
            onRoutes.put(unitVehicle, indexCount);


            TreeMap<Integer, Integer> personExit = personExitCount.get(unitVehicle);
            if (personExit == null) {
                personExit = new TreeMap<>();
                personExit.put(index, 1);
            } else {
                Integer numOfPerson = personExit.get(index);
                if (numOfPerson != null) {
                    numOfPerson++;
                } else {
                    numOfPerson = 1;
                }
                personExit.put(index, numOfPerson);
            }
            personExitCount.put(unitVehicle, personExit);

        }

    }
}
