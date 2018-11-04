package beam.analysis.plots;


import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.modality.RideHailDistanceRowModel;
import beam.analysis.plots.passengerpertrip.CarPassengerPerTrip;
import beam.analysis.plots.passengerpertrip.GenericPassengerPerTrip;
import beam.analysis.plots.passengerpertrip.IGraphPassengerPerTrip;
import beam.analysis.plots.passengerpertrip.TncPassengerPerTrip;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class DeadHeadingAnalysis implements GraphAnalysis {
    private static final Integer TNC_MAX_PASSENGERS = 6;
    private static final Integer CAR_MAX_PASSENGERS = 4;
    private static final int METERS_IN_KM = 1000;
    private static final String deadHeadingTNC0XAxisTitle = "Hour";
    private static final String deadHeadingTNC0YAxisTitle = "Distance in kilometers";
    private static final String deadHeadingXAxisTitle = "Hour";
    private static final String deadHeadingYAxisTitle = "# trips";
    private static final String fileNameBase = "rideHail";
    private static final int DEFAULT_OCCURRENCE = 1;
    private static Map<String, Map<Integer, Map<Integer, Integer>>> deadHeadingsMap = new HashMap<>();
    private static Map<Integer, Map<Integer, Double>> deadHeadingsTnc0Map = new HashMap<>();
    private static int maxPassengersSeenOnGenericCase = 0;
    private Map<String, Map<Integer, List<Event>>> vehicleEvents = new HashMap<>();
    private Map<String, Map<Integer, List<Event>>> vehicleEventsCache = new HashMap<>();
    private Double passengerVkt = 0d;
    private Double deadHeadingVkt = 0d;
    private Double repositioningVkt = 0d;
    private int reservationCount = 0;
    private static List<String> excludeModes = Arrays.asList("car", "walk", "ride_hail", "subway");

    private static String getLegendText(String graphName, int i, int bucketSize) {

        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR)) {
            return Integer.toString(i);
        } else if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)
                || graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE)) {

            if (i == 0) {
                return "repositioning";
            }
            return Integer.toString(i - 1);
        } else {
            if (i == 0) {
                return "0";
            } else {
                int start = (i - 1) * bucketSize + 1;
                int end = (i - 1) * bucketSize + bucketSize;
                return start + "-" + end;
            }
        }
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE))
            processDeadHeading(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        processDeadHeadingPassengerPerTripRemainingRepositionings();
        //createDeadHeadingPassengerPerTripGraph(event, graphType);

        for (IGraphPassengerPerTrip graph : passengerPerTripMap.values()) {
            graph.process(event);
        }
    }

    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {
        if ("TNC0".equalsIgnoreCase(graphType)) {

            processDeadHeadingDistanceRemainingRepositionings();
            createDeadHeadingDistanceGraph(event);
        } else {
            createGraph(event);
        }
    }

    @Override
    public void resetStats() {
        deadHeadingsMap.clear();
        deadHeadingsTnc0Map.clear();
        maxPassengersSeenOnGenericCase = 0;

        passengerVkt = 0d;
        deadHeadingVkt = 0d;
        repositioningVkt = 0d;
        reservationCount = 0;

        passengerPerTripMap.clear();
    }

    // Deadheading Distance Graph

    private void processDeadHeading(Event event) {

        // Process Event for "tnc_passenger_per_trip.png" graph
        processEventForTncDeadheadingDistanceGraph(event);

        // Process Event for "tnc_deadheading_distance.png" graph
        processEventForTncPassengerPerTripGraph(event);
    }

    private void processDeadHeadingDistanceRemainingRepositionings() {

        Set<String> vehicleIds = vehicleEvents.keySet();

        for (String vid : vehicleIds) {

            Map<Integer, List<Event>> vehicleData = vehicleEvents.get(vid);

            if (vehicleData != null) {
                List<Integer> hourKeys = new ArrayList<>(vehicleData.keySet());
                Collections.sort(hourKeys);

                int n = hourKeys.size();
                for (int k = 0; k < n; k++) {

                    int hourKey = hourKeys.get(k);
                    List<Event> vehicleHourData = vehicleData.get(hourKey);

                    int m = vehicleHourData.size();
                    if (k == (n - 1)) {
                        m = vehicleHourData.size() - 1;
                    }

                    for (int i = 0; i < m; i++) {

                        Event oldEvent = vehicleHourData.get(i);
                        Double length2 = Double.parseDouble(oldEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LENGTH));

                        updateDeadHeadingTNCMap(length2, hourKey, -1);
                    }
                }
            }
        }

        vehicleEvents.clear();
    }

    private void processEventForTncDeadheadingDistanceGraph(Event event) {

        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        Map<String, String> attributes = event.getAttributes();
        String mode = attributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicle_id = attributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        String graphName = getGraphNameAgainstModeAndVehicleId(mode, vehicle_id);
        Integer _num_passengers = getPathTraversalEventNumOfPassengers(attributes);

        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)) {
            Double length = Double.parseDouble(attributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH));

            if (_num_passengers > 0) {

                reservationCount++;

                Map<Integer, List<Event>> vehicleData = vehicleEvents.get(vehicle_id);

                if (vehicleData != null) {
                    List<Integer> hourKeys = new ArrayList<>(vehicleData.keySet());
                    Collections.sort(hourKeys);

                    int n = hourKeys.size();
                    for (int k = 0; k < n; k++) {

                        int hourKey = hourKeys.get(k);
                        List<Event> vehicleHourData = vehicleData.get(hourKey);

                        int m = vehicleHourData.size();
                        if (k == (n - 1)) {
                            m = vehicleHourData.size() - 1;
                        }

                        for (int i = 0; i < m; i++) {

                            Event oldEvent = vehicleHourData.get(i);
                            Double length2 = Double.parseDouble(oldEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LENGTH));

                            updateDeadHeadingTNCMap(length2, hourKey, -1);
                        }

                        if (k == (n - 1)) {
                            Event oldEvent = vehicleHourData.get(m);
                            Double length2 = Double.parseDouble(oldEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LENGTH));

                            updateDeadHeadingTNCMap(length2, hourKey, 0);
                        }
                    }
                }

                // Process the current event with num_passenger > 0 and remove any buffer of repositioning and deadheading events
                updateDeadHeadingTNCMap(length, hour, _num_passengers);
                vehicleEvents.remove(vehicle_id);
            } else {

                Map<Integer, List<Event>> vehicleData = vehicleEvents.get(vehicle_id);
                if (vehicleData == null) {
                    vehicleData = new HashMap<>();
                }

                List<Event> eventsList = vehicleData.get(hour);

                if (eventsList == null) {
                    eventsList = new ArrayList<>();
                }

                eventsList.add(event);
                vehicleData.put(hour, eventsList);
                vehicleEvents.put(vehicle_id, vehicleData);
            }


        }
    }

    private void updateDeadHeadingTNCMap(double length, int hour, Integer _num_passengers) {
        Map<Integer, Double> hourData = deadHeadingsTnc0Map.get(hour);

        if (hourData == null) {
            hourData = new HashMap<>();
            hourData.put(_num_passengers, length);
        } else {
            Double distance = hourData.get(_num_passengers);
            if (distance == null) {
                distance = length;
            } else {
                distance = distance + length;
            }
            hourData.put(_num_passengers, distance);
        }

        deadHeadingsTnc0Map.put(hour, hourData);
    }

    private void createDeadHeadingDistanceGraph(IterationEndsEvent event) throws IOException {
        double[][] dataSet = buildDeadHeadingDataSetTnc0();
        CategoryDataset tnc0DeadHeadingDataSet = DatasetUtilities.createCategoryDataset("Mode ", "", dataSet);
        createDeadHeadingGraphTnc0(tnc0DeadHeadingDataSet, event.getIteration(), GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE);


        writeToCSV(event.getIteration(), GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE);


        // Updating the model for the RideHailStats.csv
        updateRideHailStatsModel(event);
        writeRideHailStatsCSV(event);
    }

    private void updateRideHailStatsModel(IterationEndsEvent event) {
        RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.getOrDefault(event.getIteration(), new RideHailDistanceRowModel());

        model.setPassengerVkt(passengerVkt);
        model.setDeadheadingVkt(deadHeadingVkt);
        model.setRepositioningVkt(repositioningVkt);
        model.setReservationCount(reservationCount);
        GraphUtils.RIDE_HAIL_REVENUE_MAP.put(event.getIteration(), model);


    }

    private double[][] buildDeadHeadingDataSetTnc0() {
        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(deadHeadingsTnc0Map.keySet());
        int maxHour = 0;
        if (hours.size() > 0) {
            maxHour = hours.get(hours.size() - 1);
        }

        int lengthOfDataSet = TNC_MAX_PASSENGERS + 2;
        double dataSet[][] = new double[lengthOfDataSet][maxHour + 1];

        //dataSet[0] = getDeadHeadingDataSetTnc0ModeOccurrencePerHour(maxHour, -1);

        for (int i = 0; i < lengthOfDataSet; i++) {
            dataSet[i] = getDeadHeadingDataSetTnc0ModeOccurrencePerHour(maxHour, i - 1);
            //dataSet[i] = getDeadHeadingDataSetTnc0ModeOccurrencePerHour(maxHour, i - 1);
        }
        return dataSet;
    }

    private double[] getDeadHeadingDataSetTnc0ModeOccurrencePerHour(int maxHour, int outerLoopIndex) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        //String passengerCount = "p" + i;
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<Integer, Double> hourData = deadHeadingsTnc0Map.get(hour);
            if (hourData != null) {

                if (hourData.get(outerLoopIndex) == null) {
                    modeOccurrencePerHour[index] = 0;
                } else {

                    double val = hourData.get(outerLoopIndex) / METERS_IN_KM;
//                    double val = hourData.get(outerLoopIndex);
                    //val = Math.round(val * 100) / 100;
                    if (val > 0 && val < 1)
                        val = Math.ceil(val);
                    modeOccurrencePerHour[index] = val;

                }
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }

    private void createDeadHeadingGraphTnc0(CategoryDataset dataSet, int iterationNumber, String graphName) throws IOException {
        createGraph(dataSet, iterationNumber, graphName, deadHeadingTNC0XAxisTitle, deadHeadingTNC0YAxisTitle);
    }

    // Deadheading Passenger Per Trip Graph
    private void processDeadHeadingPassengerPerTripRemainingRepositionings() {

        Set<String> vehicleIds = vehicleEventsCache.keySet();

        for (String vid : vehicleIds) {
            Map<Integer, List<Event>> vehicleData = vehicleEventsCache.get(vid);

            if (vehicleData != null) {
                List<Integer> hourKeys = new ArrayList<>(vehicleData.keySet());
                Collections.sort(hourKeys);

                int n = hourKeys.size();
                for (int k = 0; k < n; k++) {

                    int hourKey = hourKeys.get(k);
                    List<Event> vehicleHourData = vehicleData.get(hourKey);

                    int m = vehicleHourData.size();
                    if (k == (n - 1)) {
                        m = vehicleHourData.size() - 1;
                    }

                    for (int i = 0; i < m; i++) {

                        Event oldEvent = vehicleHourData.get(i);

                        String mode = oldEvent.getAttributes().get(PathTraversalEvent.ATTRIBUTE_MODE);
                        String graphName = getGraphNameAgainstModeAndVehicleId(mode, vid);

                        updateNumPassengerInDeadHeadingsMap(hourKey, graphName, -1);
                    }
                }
            }
        }

        vehicleEventsCache.clear();
    }

    private void processEventForTncPassengerPerTripGraph(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        Map<String, String> attributes = event.getAttributes();
        String mode = attributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicle_id = attributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        String graphName = getGraphNameAgainstModeAndVehicleId(mode, vehicle_id);
        Integer _num_passengers = getPathTraversalEventNumOfPassengers(attributes);
        boolean validCase = isValidCase(graphName, _num_passengers);

        // Process Event for "tnc_passenger_per_trip.png" graph
        if (validCase) {

            /* Determine
             1. The repositioning event
             2. The deadheading event
             3. The passenger > 0 event
             4. Put the three types of events into the three categories repositioning, 0 and 1 category
             5. Display them on the graph
            */
            //updateNumPassengerInDeadHeadingsMap(hour,graphName,_num_passengers);

            if (_num_passengers > 0) {
                Map<Integer, List<Event>> vehicleData = vehicleEventsCache.get(vehicle_id);

                if (vehicleData != null) {
                    List<Integer> hourKeys = new ArrayList<>(vehicleData.keySet());
                    Collections.sort(hourKeys);

                    int n = hourKeys.size();
                    for (int k = 0; k < n; k++) {

                        int hourKey = hourKeys.get(k);
                        List<Event> vehicleHourData = vehicleData.get(hourKey);

                        int m = vehicleHourData.size();
                        if (k == (n - 1)) {
                            m = vehicleHourData.size() - 1;
                        }

                        for (int i = 0; i < m; i++) {
                            updateNumPassengerInDeadHeadingsMap(hourKey, graphName, -1);
                        }

                        if (k == (n - 1)) {
                            updateNumPassengerInDeadHeadingsMap(hourKey, graphName, 0);
                        }
                    }
                }

                // Process the current event with num_passenger > 0 and remove any buffer of repositioning and deadheading events
                updateNumPassengerInDeadHeadingsMap(hour, graphName, _num_passengers);
                vehicleEventsCache.remove(vehicle_id);
            } else {

                Map<Integer, List<Event>> vehicleData = vehicleEventsCache.get(vehicle_id);
                if (vehicleData == null) {
                    vehicleData = new HashMap<>();
                }

                List<Event> eventsList = vehicleData.get(hour);

                if (eventsList == null) {
                    eventsList = new ArrayList<>();
                }

                eventsList.add(event);
                vehicleData.put(hour, eventsList);
                vehicleEventsCache.put(vehicle_id, vehicleData);
            }
        }
    }

    private void updateNumPassengerInDeadHeadingsMap(int hour, String graphName, Integer _num_passengers) {

        Map<Integer, Map<Integer, Integer>> deadHeadings = deadHeadingsMap.get(graphName);
        Map<Integer, Integer> hourData = null;
        if (deadHeadings != null)
            hourData = deadHeadings.get(hour);
        else {
            deadHeadings = new HashMap<>();
        }
        if (hourData == null) {
            hourData = new HashMap<>();
            hourData.put(_num_passengers, 1);
        } else {
            Integer occurrence = hourData.get(_num_passengers);
            if (occurrence == null) {
                occurrence = DEFAULT_OCCURRENCE;
            } else {
                occurrence = occurrence + DEFAULT_OCCURRENCE;
            }
            hourData.put(_num_passengers, occurrence);
        }
        deadHeadings.put(hour, hourData);
        deadHeadingsMap.put(graphName, deadHeadings);
    }

    private void createDeadHeadingPassengerPerTripGraph(IterationEndsEvent event, String graphType) throws IOException {
        List<String> graphNamesList = GraphsStatsAgentSimEventsListener.getSortedStringList(deadHeadingsMap.keySet());
        for (String graphName : graphNamesList) {
            double[][] dataSet = buildDeadHeadingDataSet(deadHeadingsMap.get(graphName), graphName);
            CategoryDataset tncDeadHeadingDataSet = DatasetUtilities.createCategoryDataset("Mode ", "", dataSet);
            createDeadHeadingGraph(tncDeadHeadingDataSet, event.getIteration(), graphName);
        }
    }

    private double[][] buildDeadHeadingDataSet(Map<Integer, Map<Integer, Integer>> data, String graphName) {
        List<Integer> hours = new ArrayList<>(data.keySet());
        Collections.sort(hours);
        int maxHour = hours.get(hours.size() - 1);
        Integer maxPassengers;
        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR)) {
            maxPassengers = CAR_MAX_PASSENGERS;
        } else if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)) {
            maxPassengers = TNC_MAX_PASSENGERS;
        } else {
            maxPassengers = maxPassengersSeenOnGenericCase;
        }
        double dataSet[][];
        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC) || graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR)) {

            int dataSetLength = maxPassengers + 2;
            dataSet = new double[dataSetLength][maxHour + 1];
            dataSet[0] = getModeOccurrencePerHourAgainstMode(data, maxHour, -1);

            for (int i = 1; i <= maxPassengers; i++) {
                dataSet[i] = getModeOccurrencePerHourAgainstMode(data, maxHour, i - 1);
            }
        } else {

            // This loop gives the loop over all the different passenger groups, which is 1 in other cases.
            // In this case we have to group 0, 1 to 5, 6 to 10

            int bucketSize = getBucketSize();
            dataSet = new double[5][maxHour + 1];
            // We need only 5 buckets
            // The modeOccurrentPerHour array index will not go beyond 5 as all the passengers will be
            // accomodated within the 4 buckets because the index will not be incremented until all
            // passengers falling in one bucket are added into that index of modeOccurrencePerHour
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            int bucket = 0;
            for (int i = 0; i <= maxPassengers; i++) {
                modeOccurrencePerHour = getModeOccurrenceOfPassengerWithBucketSize(data, modeOccurrencePerHour, maxHour, i);
                if (i == 0 || (i % bucketSize == 0) || i == maxPassengers) {
                    dataSet[bucket] = modeOccurrencePerHour;
                    modeOccurrencePerHour = new double[maxHour + 1];
                    bucket = bucket + 1;
                }
            }
        }
        return dataSet;
    }

    private double[] getModeOccurrencePerHourAgainstMode(Map<Integer, Map<Integer, Integer>> data, int maxHour, int outerLoopIndex) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<Integer, Integer> hourData = data.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(outerLoopIndex) == null ? 0 : hourData.get(outerLoopIndex);
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }

    private double[] getModeOccurrenceOfPassengerWithBucketSize(Map<Integer, Map<Integer, Integer>> data, double[] modeOccurrencePerHour, int maxHour, int outerLoopIndex) {
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<Integer, Integer> hourData = data.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] += hourData.get(outerLoopIndex) == null ? 0 : hourData.get(outerLoopIndex);
            } else {
                modeOccurrencePerHour[index] += 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }

    private void createDeadHeadingGraph(CategoryDataset dataSet, int iterationNumber, String graphName) throws IOException {
        createGraph(dataSet, iterationNumber, graphName, deadHeadingXAxisTitle, deadHeadingYAxisTitle);
    }

    private void createGraph(CategoryDataset dataSet, int iterationNumber, String graphName, String xAxisTitle, String yAxisTitle) throws IOException {
        String fileName = getFileName(graphName, "png");
        String graphTitle = getTitle(graphName);
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataSet, graphTitle, xAxisTitle, yAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> legendItemList = getLegendItemList(graphName, dataSet.getRowCount(), getBucketSize());
        GraphUtils.plotLegendItems(plot, legendItemList, dataSet.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void writeToCSV(int iterationNumber, String graphName) {
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, getFileName(graphName, "csv"));
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            String heading = "hour,numPassengers,vkt";
            out.write(heading);
            out.newLine();

            HashSet<Integer> passengerCategories = new HashSet<>();
            int maxHour = Integer.MIN_VALUE;
            int minHour = Integer.MAX_VALUE;
            for (Integer nextHour : deadHeadingsTnc0Map.keySet()) {
                if (nextHour > maxHour) maxHour = nextHour;
                if (nextHour < minHour) minHour = nextHour;
                passengerCategories.addAll(deadHeadingsTnc0Map.get(nextHour).keySet());
            }

            Double vkt;
            for (Integer hour = minHour; hour <= maxHour; hour++) {
                for (Integer passengerKey : passengerCategories) {

                    if (deadHeadingsTnc0Map.containsKey(hour)) {

                        Map<Integer, Double> hourData = deadHeadingsTnc0Map.get(hour);

                        if (hourData.keySet().contains(passengerKey)) {
                            vkt = hourData.get(passengerKey);

                            if (passengerKey == 0) {

                                deadHeadingVkt += vkt;
                            } else if (passengerKey == -1) {

                                repositioningVkt += vkt;
                            } else {

                                passengerVkt += vkt;
                            }
                        } else {
                            vkt = 0d;
                        }
                    } else {
                        vkt = 0d;
                    }


                    out.write(hour.toString() + "," + passengerKey.toString() + "," + vkt.toString());
                    out.newLine();
                }
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    ////
    // Utility Methods
    private String getFileName(String graphName, String extension) {
        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)) {
            return "passengerPerTrip_" + fileNameBase + "." + extension;
        } else if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE)) {
            return fileNameBase + "TripDistance." + extension;
        } else {
            return "passengerPerTrip_" + graphName + "." + extension;
        }
    }

    private String getTitle(String graphName) {
        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)) {
            return "Number of Passengers per Trip [TNC]";
        } else if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE)) {
            return "Dead Heading Distance [TNC]";
        } else if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR)) {
            return "Number of Passengers per Trip [Car]";
        } else {
            return "Number of Passengers per Trip [" + graphName + "]";
        }
    }

    private int getBucketSize() {
        return (int) Math.ceil(maxPassengersSeenOnGenericCase / 4.0);
    }

    private boolean isValidCase(String graphName, int numPassengers) {
        boolean validCase = false;
        if (!graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.WALK)) {
            if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC) && numPassengers >= 0 && numPassengers <= TNC_MAX_PASSENGERS) {
                validCase = true;
            } else if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR) && numPassengers >= 0 && numPassengers <= CAR_MAX_PASSENGERS) {
                validCase = true;
            } else {
                if (maxPassengersSeenOnGenericCase < numPassengers)
                    maxPassengersSeenOnGenericCase = numPassengers;
                validCase = true;
            }
        }
        return validCase;
    }

    private List<String> getLegendItemList(String graphName, int dataSetRowCount, int bucketSize) {
        List<String> legendItemList = new ArrayList<>();
        for (int i = 0; i < dataSetRowCount; i++) {
            legendItemList.add(getLegendText(graphName, i, bucketSize));
        }
        return legendItemList;
    }

    private Integer getPathTraversalEventNumOfPassengers(Map<String, String> eventAttributes) {
        String num_passengers = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS);
        Integer _num_passengers = null;
        try {
            _num_passengers = Integer.parseInt(num_passengers);
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
        }
        return _num_passengers;
    }

    private String getGraphNameAgainstModeAndVehicleId(String mode, String vehicle_id) {
        String graphName = mode;
        if (mode.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR) && vehicle_id.contains(GraphsStatsAgentSimEventsListener.RIDE)) {
            graphName = GraphsStatsAgentSimEventsListener.TNC;
        }
        return graphName;
    }

    public int getDeadHeadingTnc0HourDataCount(int hourIndex, int hour) {
        double[][] dataSet = buildDeadHeadingDataSetTnc0();
        double[] hoursData = dataSet[hourIndex];
        return (int) Math.ceil(hoursData[hour]);
    }

    public int getBucketCountAgainstMode(int bucketIndex, String mode) {
        double[][] dataSet = buildDeadHeadingDataSet(deadHeadingsMap.get(mode), mode);
        double[] hoursData = dataSet[bucketIndex];
        double count = 0;
        for (double hourData : hoursData) {
            count = count + hourData;
        }
        return (int) Math.ceil(count);
    }

    public int getPassengerPerTripCountForSpecificHour(int bucketIndex, String mode, int hour) {
        double[][] dataSet = buildDeadHeadingDataSet(deadHeadingsMap.get(mode), mode);
        double[] hoursData = dataSet[bucketIndex];
        return (int) Math.ceil(hoursData[hour]);
    }

    public int getDeadHeadingTnc0HourDataCount(int hourIndex) {
        double[][] dataSet = buildDeadHeadingDataSetTnc0();
        double[] hoursData = dataSet[hourIndex];
        double count = 0;
        for (double hourData : hoursData) {
            count = count + hourData;
        }
        return (int) Math.ceil(count);
    }


    private void writeRideHailStatsCSV(IterationEndsEvent event) {

        String csvFileName = event.getServices().getControlerIO().getOutputFilename("rideHailStats.csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            String heading = "Iteration,rideHailRevenue,averageRideHailWaitingTimeInSeconds,totalRideHailWaitingTimeInSeconds,passengerVKT,repositioningVKT,deadHeadingVKT,averageSurgePriceLevel,maxSurgePriceLevel,reservationCount";
            out.write(heading);
            out.newLine();
            for (Integer key : GraphUtils.RIDE_HAIL_REVENUE_MAP.keySet()) {
                RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.get(key);
                double passengerVkt = model.getPassengerVkt();
                double repositioningVkt = model.getRepositioningVkt();
                double deadheadingVkt = model.getDeadheadingVkt();
                double maxSurgePricingLevel = model.getMaxSurgePricingLevel();
                double totalSurgePricingLevel = model.getTotalSurgePricingLevel();
                double surgePricingLevelCount = model.getSurgePricingLevelCount();
                double averageSurgePricing = surgePricingLevelCount == 0 ? 0 : totalSurgePricingLevel / surgePricingLevelCount;
                int reservationCount = model.getReservationCount();
                out.append(key.toString());
                out.append(",").append(String.valueOf(model.getRideHailRevenue()));
                out.append(",").append(String.valueOf(model.getRideHailWaitingTimeSum() / model.getTotalRideHailCount()));
                out.append(",").append(String.valueOf(model.getRideHailWaitingTimeSum()));
                out.append(",").append(String.valueOf(passengerVkt / 1000));
                out.append(",").append(String.valueOf(repositioningVkt / 1000));
                out.append(",").append(String.valueOf(deadheadingVkt / 1000));
                out.append(",").append(String.valueOf(averageSurgePricing));
                out.append(",").append(String.valueOf(maxSurgePricingLevel));
                out.append(",").append(String.valueOf(reservationCount));
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            System.out.println("CSV generation failed." + e);
        }
    }


    //
    // New Code
    public void collectEvents(Event event) {
        String type = event.getEventType();
        // We care only about PathTraversalEvent!
        if (!type.equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE))
            return;

        Map<String, String> attributes = event.getAttributes();
        String mode = getEventMode(attributes);
        String vehicleId = getVehicleId(attributes);

        if (mode.equalsIgnoreCase("car") && !vehicleId.contains("ride")) {
            IGraphPassengerPerTrip graph = passengerPerTripMap.get("car");
            if (graph == null) {
                graph = new CarPassengerPerTrip("car");
            }
            graph.collectEvent(event, attributes);

            passengerPerTripMap.put("car", graph);

        } else if (mode.equalsIgnoreCase("car") && vehicleId.contains("ride")) {
            IGraphPassengerPerTrip graph = passengerPerTripMap.get("tnc");
            if (graph == null) {
                graph = new TncPassengerPerTrip();
            }
            graph.collectEvent(event, attributes);

            passengerPerTripMap.put("tnc", graph);
        } else {
            if (!excludeModes.contains(mode)) {
                IGraphPassengerPerTrip graph = passengerPerTripMap.get(mode);
                if (graph == null) {
                    graph = new GenericPassengerPerTrip(mode);
                }
                graph.collectEvent(event, attributes);
                passengerPerTripMap.put(mode, graph);
            }
        }
    }

    Map<String, IGraphPassengerPerTrip> passengerPerTripMap = new HashMap<>();

    private String getVehicleId(Map<String, String> attributes) {
        return attributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
    }

    private String getEventMode(Map<String, String> attributes) {
        return attributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
    }

}
