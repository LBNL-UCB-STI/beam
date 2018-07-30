package beam.analysis.plots;

import beam.agentsim.events.LeavingParkingEvent;
import beam.agentsim.events.ParkEvent;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;


public class AverageVehicleParkingStats implements IGraphStats {
    private static Map<String, Map<String, String>> vehicleEnterTime = new HashMap<>();
    private static Map<Integer, Map<String, Double>> vehicleOccupancy = new HashMap<>();
    private static Map<Integer, Map<String, Integer>> vehicleOccupancyCount = new HashMap<>();

    private static Map<Integer, Map<String, Double>> avgVehicleOccupancy = new HashMap<>();
    private static Map<Integer, Map<String, Double>> parkingOccupancyInIteration = new HashMap<>();

    private static Set<String> parkingTypeSet = new HashSet();
    private static Set<String> iterationTypeSet = new HashSet();
    private static final String graphTitle = "Parking Occupancy Stats";
    private static final String xAxisTitle = "Time";
    private static final String yAxisTitle = "# avg occupancy in sec ";
    private static final String fileName = "average_parking_occupancy";
    private Logger log = LoggerFactory.getLogger(this.getClass());

    @Override
    public void processStats(Event event) {
        processVehicleParking(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        collectEventsForPendingPark();
        calculateAvgParking();
        updateParkingOccupancyInIteration(event.getIteration());
        CategoryDataset dataset = buildParkTypeOccupancyDatasetForGraph();
        if (dataset != null)
            createVehicleOccupancyGraph(dataset, event.getIteration());
        writeToCSV(event);
    }

    public void calculateAvgParking() {
        Set<Integer> hours = vehicleOccupancy.keySet();
        Set<Integer> hours1 = vehicleOccupancyCount.keySet();
        assert hours.size() == hours1.size();

        for (Integer hour : hours) {
            Map<String, Double> vehicleOccupancyInParking = vehicleOccupancy.get(hour);
            Map<String, Integer> vehicleOccupancyInParkingCount = vehicleOccupancyCount.get(hour);
            Set<String> parkings = vehicleOccupancyInParking.keySet();
            Set<String> parkings1 = vehicleOccupancyInParkingCount.keySet();
            assert parkings.size() == parkings1.size();
            Map<String, Double> avgTimeInParking = new HashMap<>();
            for (String parking : parkings) {
                Double time = vehicleOccupancyInParking.get(parking);
                Integer count = vehicleOccupancyInParkingCount.get(parking);

                if (time != null && count != null) {
                    Double avg = time / count;
                    avgTimeInParking.put(parking, avg);
                    avgVehicleOccupancy.put(hour, avgTimeInParking);
                }

            }
        }
    }


    public void updateParkingOccupancyInIteration(Integer iteration) {
        Set<Integer> hours = avgVehicleOccupancy.keySet();
        Map<String, Double> totalOccupancyInParking = new HashMap<>();
        for (Integer hour : hours) {
            Map<String, Double> parkingTypeOccupancy = avgVehicleOccupancy.get(hour);
            Set<String> parkingTypes = parkingTypeOccupancy.keySet();
            for (String parkingType : parkingTypes) {
                Double time = parkingTypeOccupancy.get(parkingType);
                Double t = totalOccupancyInParking.get(parkingType);
                if (t == null) {
                    totalOccupancyInParking.put(parkingType, time);
                } else {
                    totalOccupancyInParking.put(parkingType, time + t);
                }

            }
        }
        iterationTypeSet.add("it." + iteration);
        parkingOccupancyInIteration.put(iteration, totalOccupancyInParking);

    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {
        throw new IOException("Not implemented");
    }

    @Override
    public void resetStats() {
        vehicleEnterTime.clear();
        vehicleOccupancy.clear();
        parkingTypeSet.clear();
        avgVehicleOccupancy.clear();
        vehicleOccupancyCount.clear();
    }

    private void collectEventsForPendingPark() {

        Set<String> vehicleIds = vehicleEnterTime.keySet();
        for (String vehicleId : vehicleIds) {
            Map<String, String> timeInParkingType = vehicleEnterTime.get(vehicleId);
            Set<String> parkingTypes = timeInParkingType.keySet();
            for (String parkingType : parkingTypes) {
                double parkingTime = Double.parseDouble(timeInParkingType.get(parkingType));
                int hour = GraphsStatsAgentSimEventsListener.getEventHour(parkingTime);
                for (int i = hour; i < 24; i++) {
                    double time;
                    if (i == hour) {
                        time = (i + 1) * 3600 - parkingTime;
                    } else {
                        time = 3600;
                    }
                    updateVehicleOccupancyCount(parkingType, i);

                    updateVehicleOccupancy(parkingType, time, i);
                }
            }
        }
    }

    private void processVehicleParking(Event event) {

        if (event.getEventType() == ParkEvent.EVENT_TYPE) {

            String vehicleID = event.getAttributes().get(ParkEvent.ATTRIBUTE_VEHICLE_ID);
            String parkingType = event.getAttributes().get(ParkEvent.ATTRIBUTE_PARKING_TYPE);
            String time = event.getAttributes().get(ParkEvent.ATTRIBUTE_TIME);
            parkingTypeSet.add(parkingType);
            Map<String, String> parkingTime = new HashMap<>();
            parkingTime.put(parkingType, time);
            vehicleEnterTime.put(vehicleID, parkingTime);

        }

        if (event.getEventType() == LeavingParkingEvent.EVENT_TYPE) {

            String vehicleID = event.getAttributes().get(LeavingParkingEvent.ATTRIBUTE_VEHICLE_ID);
            String parkingType = event.getAttributes().get(LeavingParkingEvent.ATTRIBUTE_PARKING_TYPE);
            String leavingTime = event.getAttributes().get(LeavingParkingEvent.ATTRIBUTE_TIME);
            double leavingTimeInDouble = Double.parseDouble(leavingTime);
            double parkingTimeInDouble;

            Map<String, String> vehicleEnter = vehicleEnterTime.get(vehicleID);
            if (vehicleEnter == null) {
                parkingTimeInDouble = 0; // this is because this vehicle is leaving from current parking(first event of vehicle)
            } else {
                String vehicleEnterTime = vehicleEnter.get(parkingType);
                parkingTimeInDouble = Double.parseDouble(vehicleEnterTime);
            }

            int parkingTimeHour = GraphsStatsAgentSimEventsListener.getEventHour(parkingTimeInDouble);

            double duration = leavingTimeInDouble - parkingTimeInDouble;
            updateVehicleOccupancy(parkingType, duration, parkingTimeHour);
            updateVehicleOccupancyCount(parkingType, parkingTimeHour);

            vehicleEnterTime.remove(vehicleID);
        }
    }


    private void updateVehicleOccupancy(String parkingType, Double parkingTime, int hour) {

        Map<String, Double> parkingOccupancy = vehicleOccupancy.get(hour);
        if (parkingOccupancy == null) {
            parkingOccupancy = new HashMap<>();
            parkingOccupancy.put(parkingType, parkingTime);
        } else {
            Double previousParkingTime = parkingOccupancy.get(parkingType);
            if (previousParkingTime == null) {
                parkingOccupancy.put(parkingType, parkingTime);
            } else {
                parkingOccupancy.put(parkingType, parkingTime + previousParkingTime);
            }
        }
        vehicleOccupancy.put(hour, parkingOccupancy);
    }


    private void updateVehicleOccupancyCount(String parkingType, int hour) {

        Map<String, Integer> parkingOccupancy = vehicleOccupancyCount.get(hour);
        if (parkingOccupancy == null) {
            parkingOccupancy = new HashMap<>();
            parkingOccupancy.put(parkingType, 1);
        } else {
            Integer previousParking = parkingOccupancy.get(parkingType);
            if (previousParking == null) {
                parkingOccupancy.put(parkingType, 1);
            } else {
                parkingOccupancy.put(parkingType, previousParking + 1);
            }
        }
        vehicleOccupancyCount.put(hour, parkingOccupancy);
    }


    private double[][] buildParkingTypeOccupancyDataset() {

        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(avgVehicleOccupancy.keySet());
        List<String> parkingChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(parkingTypeSet);
        if (0 == hoursList.size())
            return null;
        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[parkingTypeSet.size()][];
        for (int i = 0; i < parkingChosenList.size(); i++) {
            String parkingType = parkingChosenList.get(i);
            dataset[i] = getParkingDataPerOccurrenceAgainstParkingType(avgVehicleOccupancy, parkingType, maxHour);
        }
        return dataset;
    }

    private CategoryDataset buildParkTypeOccupancyDatasetForGraph() {
        CategoryDataset categoryDataset1 = null;
        double[][] dataset1 = buildParkingTypeOccupancyDataset();
        if (dataset1 != null)
            categoryDataset1 = createCategoryDataset("", dataset1);

        return categoryDataset1;
    }


    private void createVehicleOccupancyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        boolean legend = true;


        final JFreeChart chart = GraphUtils.createBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, legend);

        CategoryPlot plot = chart.getCategoryPlot();

        List<String> parkingAreatype = new ArrayList<>();
        parkingAreatype.addAll(parkingTypeSet);
        Collections.sort(parkingAreatype);
        GraphUtils.plotLegendItems(plot, parkingAreatype, dataset.getRowCount());

        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);

    }

    private void writeToCSV(IterationEndsEvent event) {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(event.getIteration(), fileName + ".csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            String heading = parkingTypeSet.stream().reduce((x, y) -> x + "," + y).get();
            out.write("hours," + heading);
            out.newLine();

            int max = avgVehicleOccupancy.keySet().stream().mapToInt(x -> x).max().getAsInt();

            for (int hour = 0; hour <= max; hour++) {
                Map<String, Double> parkingData = avgVehicleOccupancy.get(hour);
                StringBuilder builder = new StringBuilder(hour + 1 + "");
                if (parkingData != null) {
                    for (String parking : parkingTypeSet) {
                        if (parkingData.get(parking) != null) {
                            builder.append("," + parkingData.get(parking));
                        } else {
                            builder.append(",0");
                        }
                    }
                } else {
                    for (String park : parkingTypeSet) {
                        builder.append(",0");
                    }
                }
                out.write(builder.toString());
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("CSV generation failed.", e);
        }
    }

    public void notifyShutdown(ShutdownEvent event) throws Exception {
        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();
        String fileName = outputDirectoryHierarchy.getOutputFilename("parkingOccupancy.png");
        CategoryDataset dataset = buildParkingTypeOccupancyDatasetForGraph();
        if (dataset != null)
            createParkingOccupancyGraph(dataset, fileName);
    }

    private CategoryDataset buildParkingTypeOccupancyDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildTotalParkingTypeOccupancyDataset();

        if (dataset != null) {
            categoryDataset = createCategoryDataset("it.", dataset);
        }
        return categoryDataset;
    }

    public CategoryDataset createCategoryDataset(String columnKeyPrefix, double[][] data) {

        DefaultCategoryDataset result = new DefaultCategoryDataset();
        for (int r = 0; r < data.length; r++) {
            String rowKey = String.valueOf(r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue(data[r][c], rowKey, columnKey);
            }
        }
        return result;
    }

    private double[][] buildTotalParkingTypeOccupancyDataset() {

        List<Integer> iterationList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(parkingOccupancyInIteration.keySet());
        List<String> parkingChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(parkingTypeSet);
        if (0 == iterationList.size())
            return null;
        Integer maxIteration = iterationList.get(iterationList.size() - 1);
        double[][] dataset = new double[parkingTypeSet.size()][];
        for (int i = 0; i < parkingChosenList.size(); i++) {
            String parkingType = parkingChosenList.get(i);
            dataset[i] = getParkingDataPerOccurrenceAgainstParkingType(parkingOccupancyInIteration, parkingType, maxIteration);
        }
        return dataset;
    }


    private double[] getParkingDataPerOccurrenceAgainstParkingType(Map<Integer, Map<String, Double>> parkingOccupancyInIteration, String parkingType, int maxIteration) {
        double[] occurrenceAgainstParkingType = new double[maxIteration + 1];
        int index = 0;
        for (int iteration = 0; iteration <= maxIteration; iteration++) {
            Map<String, Double> parkingTypeOccupancy = parkingOccupancyInIteration.get(iteration);
            if (parkingTypeOccupancy != null) {
                occurrenceAgainstParkingType[index] = parkingTypeOccupancy.get(parkingType) == null ? 0 : parkingTypeOccupancy.get(parkingType);
            } else {
                occurrenceAgainstParkingType[index] = 0;
            }
            index = index + 1;
        }
        return occurrenceAgainstParkingType;
    }

    private void createParkingOccupancyGraph(CategoryDataset dataset, String fileName) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, "Iteration", "occupancy", fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> parkingAreatype = new ArrayList<>();
        parkingAreatype.addAll(parkingTypeSet);
        Collections.sort(parkingAreatype);
        GraphUtils.plotLegendItems(plot, parkingAreatype, dataset.getRowCount());
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

}