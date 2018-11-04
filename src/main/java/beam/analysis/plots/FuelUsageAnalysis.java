package beam.analysis.plots;


import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.via.CSVWriter;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class FuelUsageAnalysis implements GraphAnalysis, IterationSummaryAnalysis {
    private static final String graphTitle = "Energy Use by Mode";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Energy Use [MJ]";
    private static final String fileName = "energy_use.png";
    private Set<String> modesFuel = new TreeSet<>();
    private Map<Integer, Map<String, Double>> hourModeFuelage = new HashMap<>();
    private Map<String, Double> fuelConsumedByFuelType = new HashMap<>();

    private final StatsComputation<Tuple<Map<Integer, Map<String, Double>>, Set<String>>, double[][]> statsComputation;

    public FuelUsageAnalysis(StatsComputation<Tuple<Map<Integer, Map<String, Double>>, Set<String>>, double[][]> statsComputation) {
        this.statsComputation = statsComputation;
    }

    public static class FuelUsageStatsComputation implements StatsComputation<Tuple<Map<Integer, Map<String, Double>>, Set<String>>, double[][]> {
        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Double>>, Set<String>> stat) {
            List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(stat.getFirst().keySet());
            List<String> modesFuelList = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.getSecond());
            int maxHour = hours.get(hours.size() - 1);
            double[][] dataset = new double[stat.getSecond().size()][maxHour + 1];
            for (int i = 0; i < modesFuelList.size(); i++) {
                String modeChosen = modesFuelList.get(i);
                dataset[i] = getFuelageHourDataAgainstMode(modeChosen, maxHour, stat.getFirst());
            }
            return dataset;
        }

        private double[] getFuelageHourDataAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Double>> stat) {
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            int index = 0;
            for (int hour = 0; hour <= maxHour; hour++) {
                Map<String, Double> hourData = stat.get(hour);
                if (hourData != null) {
                    modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
                } else {
                    modeOccurrencePerHour[index] = 0;
                }
                index = index + 1;
            }
            return modeOccurrencePerHour;
        }
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE))
            processFuelUsage(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        CategoryDataset modesFuelageDataSet = buildModesFuelageGraphDataset();
        createModesFuelageGraph(modesFuelageDataSet, event.getIteration());
        createFuelCSV(hourModeFuelage, event.getIteration());
    }

    @Override
    public void resetStats() {
        hourModeFuelage.clear();
        modesFuel.clear();
        fuelConsumedByFuelType.clear();
    }

    private CategoryDataset buildModesFuelageGraphDataset() {
        double[][] dataset = compute();
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    double[][] compute() {
        return statsComputation.compute(new Tuple<>(hourModeFuelage, modesFuel));
    }

    private void processFuelUsage(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        Map<String, String> eventAttributes = event.getAttributes();
        String vehicleType = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
        String originalMode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicleId = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        double lengthInMeters = Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH));
        String fuelString = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_FUEL);

        String mode = originalMode;
        if (mode.equalsIgnoreCase("car") && vehicleId.contains("rideHailVehicle")) {
            mode = "rideHail";
        }
        modesFuel.add(mode);
        try {
            Double fuel = PathTraversalSpatialTemporalTableGenerator.getFuelConsumptionInMJ(vehicleId, originalMode, fuelString, lengthInMeters, vehicleType);
            Map<String, Double> hourData = hourModeFuelage.get(hour);
            if (hourData == null) {
                hourData = new HashMap<>();
                hourData.put(mode, fuel);
                hourModeFuelage.put(hour, hourData);
            } else {
                Double fuelage = hourData.get(mode);
                if (fuelage == null) {
                    fuelage = fuel;
                } else {
                    fuelage = fuelage + fuel;
                }

                hourData.put(mode, fuelage);
                hourModeFuelage.put(hour, hourData);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        String fuelType = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_FUEL_TYPE);
        double fuel = Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_FUEL));
        fuelConsumedByFuelType.merge(fuelType, fuel, (d1, d2) -> d1 + d2);
    }

    private void createModesFuelageGraph(CategoryDataset dataset, int iterationNumber) throws IOException {

        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, true);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesFuelList = new ArrayList<>(modesFuel);
        Collections.sort(modesFuelList);
        GraphUtils.plotLegendItems(plot, modesFuelList, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        return fuelConsumedByFuelType.entrySet().stream().collect(Collectors.toMap(
                e -> "fuelConsumedInMJ_" + e.getKey(),
                e -> e.getValue()/1.0e6
        ));
    }

    private void createFuelCSV(Map<Integer, Map<String, Double>> hourModeFuelage, int iterationNumber) {

        String SEPERATOR = ",";

        CSVWriter csvWriter = new CSVWriter(GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "energy_use.csv"));
        BufferedWriter bufferedWriter = csvWriter.getBufferedWriter();


        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFuelage.keySet());
        List<String> modesFuelList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesFuel);

        int maxHour = hours.get(hours.size() - 1);
        //double[][] dataset = new double[modesFuel.size()][maxHour + 1];

        try {


            bufferedWriter.append("Modes");
            bufferedWriter.append(SEPERATOR);
            for (int j = 0; j < maxHour; j++) {
                bufferedWriter.append("Bin_")
                        .append(String.valueOf(j))
                        .append(SEPERATOR);
            }
            bufferedWriter.append("\n");

            for (String modeChosen : modesFuelList) {
                bufferedWriter.append(modeChosen);
                bufferedWriter.append(SEPERATOR);

                for (int j = 0; j < maxHour; j++) {
                    Map<String, Double> modesData = hourModeFuelage.get(j);


                    String modeHourValue = "0";

                    if (modesData != null) {
                        if (modesData.get(modeChosen) != null) {
                            modeHourValue = modesData.get(modeChosen).toString();
                        }
                    }

                    bufferedWriter.append(modeHourValue);
                    bufferedWriter.append(SEPERATOR);
                }
                bufferedWriter.append("\n");
            }
            bufferedWriter.flush();
            csvWriter.closeFile();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
