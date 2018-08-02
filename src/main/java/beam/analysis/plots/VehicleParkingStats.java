package beam.analysis.plots;

import beam.agentsim.events.LeavingParkingEvent;
import beam.agentsim.events.ParkEvent;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class VehicleParkingStats implements IGraphStats {

    private static Map<String, Map<String, String>> vehicleEnterTime = new HashMap<>();
    private static Map<Integer, Map<String, Double>> vehicleOccupancy = new HashMap<>();
    private static Map<Integer, Map<String,Integer>> vehicleOccupancyCount = new HashMap<>();
    private static Set<String> parkingTypeSet = new HashSet();
    private static final String graphTitle = "Vehicle Parking Stats";
    private static final String xAxisTitle = "Time";
    private static final String yAxisTitle = "# number of vehicles ";
    private static final String fileName = "vehicle_parking_occupancy";
    private Logger log = LoggerFactory.getLogger(this.getClass());


    @Override
    public void processStats(Event event) {
        processVehicleParking(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        ;
        collectEventsForPendingPark();
        CategoryDataset dataset = buildParkTypeOccupancyDatasetForGraph();
        if (dataset != null)
            createVehicleOccupancyGraph(dataset, event.getIteration());
        writeToCSV(event);
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
                    updateVehicleOccupancyCount(parkingType,i);
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

            double parkingTimeInDouble;
            Map<String, String> vehicleEnter = vehicleEnterTime.get(vehicleID);
            if (vehicleEnter == null) {
                parkingTimeInDouble = 0; // this is because this vehicle is leaving from current parking(first event of vehicle)
            } else {
                String vehicleEnterTime = vehicleEnter.get(parkingType);
                parkingTimeInDouble = Double.parseDouble(vehicleEnterTime);
            }

            double leavingTimeInDouble = Double.parseDouble(leavingTime);

            int parkingTimeHour = GraphsStatsAgentSimEventsListener.getEventHour(parkingTimeInDouble);
            int leavingTimeHour = GraphsStatsAgentSimEventsListener.getEventHour(leavingTimeInDouble);

            for (int hour = parkingTimeHour; hour < leavingTimeHour; hour++) {

                updateVehicleOccupancyCount(parkingType,hour);
            }

        }
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

    private double[] getHoursDataPerOccurrenceAgainstParkingType(String parkingType, int maxHour) {
        double[] OccurrenceAgainstParkingType = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Integer> hourOccupancy = vehicleOccupancyCount.get(hour);
            if (hourOccupancy != null) {
                OccurrenceAgainstParkingType[index] = hourOccupancy.get(parkingType) == null ? 0 : hourOccupancy.get(parkingType);
            } else {
                OccurrenceAgainstParkingType[index] = 0;
            }
            index = index + 1;
        }
        return OccurrenceAgainstParkingType;
    }

    private double[][] buildParkingTypeOccupancyDataset() {

        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(vehicleOccupancyCount.keySet());
        List<String> parkingChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(parkingTypeSet);
        if (0 == hoursList.size())
            return null;
        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[parkingTypeSet.size()][maxHour + 1];
        for (int i = 0; i < parkingChosenList.size(); i++) {
            String parkingType = parkingChosenList.get(i);
            dataset[i] = getHoursDataPerOccurrenceAgainstParkingType(parkingType, maxHour);
        }
        return dataset;
    }

    private CategoryDataset buildParkTypeOccupancyDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildParkingTypeOccupancyDataset();
        if (dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Mode ", "", dataset);

        return categoryDataset;
    }

    private void createVehicleOccupancyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, legend);
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

            int max = vehicleOccupancyCount.keySet().stream().mapToInt(x -> x).max().getAsInt();

            for (int hour = 0; hour <= max; hour++) {
                Map<String, Integer> parkingData = vehicleOccupancyCount.get(hour);
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
}