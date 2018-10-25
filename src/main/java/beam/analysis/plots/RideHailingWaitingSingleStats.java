package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.sim.config.BeamConfig;
import beam.utils.DebugLib;
import org.jfree.chart.JFreeChart;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.misc.Time;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * @author abid
 */
public class RideHailingWaitingSingleStats implements BeamStats {

    private static final String graphTitle = "Ride Hail Waiting Time";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Waiting Time (seconds)";
    private static final String fileName = "rideHail_waitingSingleStats";
    private static double numberOfTimeBins;
    private double lastMaximumTime = 0;
    private Map<String, Event> rideHailWaiting = new HashMap<>();

    private Map<Integer, Double> hoursTimesMap = new HashMap<>();
    private final StatsComputation<Map<Integer, Double>, double[][]> statComputation;

    public static class RideHailingWaitingSingleComputation implements StatsComputation<Map<Integer, Double>, double[][]> {

        @Override
        public double[][] compute(Map<Integer, Double> stat) {
            Double _numberOfTimeBins = numberOfTimeBins;
            int maxHour = _numberOfTimeBins.intValue();

            double[][] data = new double[1][maxHour];
            for (Integer key : stat.keySet()) {

                if (key >= data[0].length) {
                    DebugLib.emptyFunctionForSettingBreakPoint();
                }

                data[0][key] = stat.get(key);
            }
            return data;
        }
    }

    public RideHailingWaitingSingleStats(BeamConfig beamConfig, StatsComputation<Map<Integer, Double>, double[][]> statComputation) {
        this.statComputation = statComputation;

        double endTime = Time.parseTime(beamConfig.matsim().modules().qsim().endTime());
        double timeBinSizeInSec = beamConfig.beam().agentsim().agents().rideHail().iterationStats().timeBinSizeInSec();

        numberOfTimeBins = Math.floor(endTime / timeBinSizeInSec);
    }

    @Override
    public void resetStats() {
        lastMaximumTime = 0;

        rideHailWaiting.clear();
        hoursTimesMap.clear();
    }

    @Override
    public void processStats(Event event) {

        if (event instanceof ModeChoiceEvent) {

            String mode = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_MODE);
            if (mode.equalsIgnoreCase("ride_hail")) {

                ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) event;
                Id<Person> personId = modeChoiceEvent.getPersonId();
                rideHailWaiting.put(personId.toString(), event);
            }
        } else if (event instanceof PersonEntersVehicleEvent) {

            PersonEntersVehicleEvent personEntersVehicleEvent = (PersonEntersVehicleEvent) event;
            Id<Person> personId = personEntersVehicleEvent.getPersonId();
            String _personId = personId.toString();

            if (rideHailWaiting.containsKey(personId.toString())) {

                ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) rideHailWaiting.get(_personId);
                double difference = personEntersVehicleEvent.getTime() - modeChoiceEvent.getTime();
                processRideHailingWaitingTimes(modeChoiceEvent, difference);

                // Remove the personId from the list of ModeChoiceEvent
                rideHailWaiting.remove(_personId);
            }
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        double[][] data = statComputation.compute(hoursTimesMap);
        CategoryDataset dataset = DatasetUtilities.createCategoryDataset("", "", data);
        if (dataset != null)
            createModesFrequencyGraph(dataset, event.getIteration());

        writeToCSV(event.getIteration(), hoursTimesMap);
    }

    private void processRideHailingWaitingTimes(Event event, double waitingTime) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());

        //waitingTime = waitingTime / 60;

        if (waitingTime > lastMaximumTime) {
            lastMaximumTime = waitingTime;
        }

        Double timeList = hoursTimesMap.get(hour);
        if (timeList == null) {
            timeList = waitingTime;
        } else {
            timeList += waitingTime;
        }
        hoursTimesMap.put(hour, timeList);
    }


    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {

        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName + ".png", false);

        GraphUtils.setColour(chart, 1);
        // Writing graph to image file
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }


    private void writeToCSV(int iterationNumber, Map<Integer, Double> hourModeFrequency) {
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            String heading = "WaitingTime(sec),Hour";
            out.write(heading);
            out.newLine();

            for (int i = 0; i < numberOfTimeBins; i++) {

                Double inner = hourModeFrequency.get(i);
                String line = (inner == null) ? "0" : "" + Math.round(inner * 100.0) / 100.0;
                line += "," + (i + 1);
                out.write(line);
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
