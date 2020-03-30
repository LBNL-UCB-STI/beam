package beam.analysis.physsim;

import beam.analysis.plot.PlotGraph;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.sim.config.BeamConfig;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.core.utils.misc.Time;

import java.util.*;

public class PhyssimTripStats implements PersonArrivalEventHandler, PersonDepartureEventHandler {

    private static final List<String> vehicleType = new ArrayList<>(Arrays.asList("body", "rideHail", "others"));

    private static final String fileName = "physsimTripHistogram";
    private static final String xAxisLabel = "time (binSize=<?> sec)";

    private static Map<String, TreeMap<Integer, Integer>> personEnterCount = new HashMap<>();
    private static Map<String, TreeMap<Integer, Integer>> personExitCount = new HashMap<>();
    private static Map<String, TreeMap<Integer, Integer>> onRoutes = new HashMap<>();

    private static Map<String, Integer> modePerson = new HashMap<>();
    private PlotGraph plotGraph = new PlotGraph();
    private final boolean writeGraph;
    private int binSize;
    private int numOfBins;

    public PhyssimTripStats(BeamConfig beamConfig){
        binSize = beamConfig.beam().outputs().stats().binSize();
        String endTime = beamConfig.matsim().modules().qsim().endTime();
        double _endTime = Time.parseTime(endTime);
        double _numOfTimeBins = _endTime / binSize;
        _numOfTimeBins = Math.floor(_numOfTimeBins);
        numOfBins = (int) _numOfTimeBins + 1;
        writeGraph = beamConfig.beam().outputs().writeGraphs();
    }


    @Override
    public void handleEvent(PersonDepartureEvent event) {
        int index = plotGraph.getBinIndex(event.getTime(), this.binSize, this.numOfBins);
        String vehicleId = event.getLegMode();

        if (vehicleId.contains(":")) {
            String v = vehicleId.split(":")[0];
            if (!vehicleType.contains(v)) {
                vehicleType.add(v);
            }
        }

        String unitVehicle;
        boolean isDigit = vehicleId.replace("-", "").chars().allMatch(Character::isDigit);
        if (isDigit || vehicleId.equals("car")) {
            unitVehicle = "car";
        } else {
            unitVehicle = vehicleType.stream().filter(vehicleId::contains).findAny().orElse("others");
        }

        int count = modePerson.merge(unitVehicle, 1, Integer::sum);
        TreeMap<Integer, Integer> indexCount = onRoutes.getOrDefault(unitVehicle, new TreeMap<>());
        indexCount.put(index, count);
        onRoutes.put(unitVehicle, indexCount);

        TreeMap<Integer, Integer> personEnter = personEnterCount.getOrDefault(unitVehicle, new TreeMap<>());
        personEnter.merge(index, 1, Integer::sum);
        personEnterCount.put(unitVehicle, personEnter);
   }

    @Override
    public void handleEvent(PersonArrivalEvent event) {
        int index = plotGraph.getBinIndex(event.getTime(), this.binSize, this.numOfBins);
        String vehicleId = event.getLegMode();

        String unitVehicle;
        boolean isDigit = vehicleId.replace("-", "").chars().allMatch(Character::isDigit);
        if (isDigit || vehicleId.equals("car")) {
            unitVehicle = "car";
        } else {
            unitVehicle = vehicleType.stream().filter(vehicleId::contains).findAny().orElse("others");
        }

        Integer count = modePerson.merge(unitVehicle, -1, Integer::sum);
        TreeMap<Integer, Integer> indexCount = onRoutes.get(unitVehicle);
        if (indexCount != null) {
            indexCount.put(index, count);
        }
        onRoutes.put(unitVehicle, indexCount);

        TreeMap<Integer, Integer> personExit = personExitCount.getOrDefault(unitVehicle, new TreeMap<>());
        personExit.merge(index, 1, Integer::sum);
        personExitCount.put(unitVehicle, personExit);

    }

    @Override
    public void reset(int iteration) {

        for (String mode : onRoutes.keySet()) {
            if (!(personEnterCount.size() == 0 && personExitCount.size() == 0) && writeGraph){
                plotGraph.writeGraphic(GraphsStatsAgentSimEventsListener.CONTROLLER_IO, iteration, mode, fileName, personEnterCount, personExitCount, onRoutes, xAxisLabel, binSize);
            }
        }
    }
}
