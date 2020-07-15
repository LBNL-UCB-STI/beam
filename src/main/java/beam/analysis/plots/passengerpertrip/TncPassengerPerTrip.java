package beam.analysis.plots.passengerpertrip;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.GraphUtils;
import org.jfree.data.category.CategoryDataset;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.*;

public class TncPassengerPerTrip implements IGraphPassengerPerTrip{

    final String graphName = "tnc";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# trips";
    private static final int DEFAULT_OCCURRENCE = 1;

    int eventCounter = 0;
    int passengerCounter = 0;
    int maxHour = 0;

    private final Map<String, Map<Integer, List<Event>>> vehicleEventsCache = new HashMap<>();
    private final Map<String, Map<Integer, Map<Integer, Integer>>> deadHeadingsMap = new HashMap<>();

    int maxPassengersSeenOnGenericCase = 0;

    public TncPassengerPerTrip(){ }

    @Override
    public void collectEvent(PathTraversalEvent event) {

        eventCounter++;

        int hour = getEventHour(event.getTime());
        String mode = event.mode().value();
        String vehicle_id = event.vehicleId().toString();
        Integer _num_passengers = event.numberOfPassengers();

        passengerCounter = passengerCounter + _num_passengers;

        maxPassengersSeenOnGenericCase = maxPassengersSeenOnGenericCase < _num_passengers ? _num_passengers : maxPassengersSeenOnGenericCase;

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

                    vehicleEventsCache.remove(vehicle_id);
                }

                // Process the current event with num_passenger > 0 and remove any buffer of repositioning and deadheading events
                updateNumPassengerInDeadHeadingsMap(hour, graphName, _num_passengers);
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

    @Override
    public void process(IterationEndsEvent event) throws IOException {
        processDeadHeadingPassengerPerTripRemainingRepositionings();

        double[][] matrixDataSet = buildDeadHeadingDataSet(deadHeadingsMap.get(graphName));

        CategoryDataset dataSet = GraphUtils.createCategoryDataset("Mode", "", matrixDataSet);
        draw(dataSet, event.getIteration(), xAxisTitle, yAxisTitle, event.getServices().getControlerIO());

        writeCSV(matrixDataSet, event.getIteration(), event.getServices().getControlerIO());
    }

    private double[][] buildDeadHeadingDataSet(Map<Integer, Map<Integer, Integer>> data) {
        List<Integer> hours = new ArrayList<>(data.keySet());
        Collections.sort(hours);
        int maxHour = hours.get(hours.size() - 1);
        int maxPassengers = TNC_MAX_PASSENGERS;

        double[][] dataSet;

        int dataSetLength = maxPassengers + 2;
        dataSet = new double[dataSetLength][maxHour + 1];
        dataSet[0] = getModeOccurrencePerHourAgainstMode(data, maxHour, -1);

        for (int i = 1; i <= maxPassengers; i++) {
            dataSet[i] = getModeOccurrencePerHourAgainstMode(data, maxHour, i - 1);
        }
        return dataSet;
    }

    @Override
    public String getFileName(String extension) {
        return "passengerPerTripRideHail." + extension;
    }

    @Override
    public String getTitle() {
        return "Number of Passengers per Trip [TNC]";
    }

    @Override
    public String getLegendText(int i) {
        if (i == 0) {
            return "repositioning";
        }
        return Integer.toString(i - 1);
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

    @Override
    public boolean isValidCase(String graphName, int numPassengers) {
        return numPassengers <= TNC_MAX_PASSENGERS;
    }

    // Deadheading Passenger Per Trip Graph
    private void processDeadHeadingPassengerPerTripRemainingRepositionings() {

        Set<String> vehicleIds = vehicleEventsCache.keySet();

        for (String vid : vehicleIds) {
            Map<Integer, List<Event>> vehicleData = vehicleEventsCache.get(vid);

            if (vehicleData != null) {
                List<Integer> hourKeys = new ArrayList<>(vehicleData.keySet());
                Collections.sort(hourKeys);

                for (Integer hourKey : hourKeys) {
                    List<Event> vehicleHourData = vehicleData.get(hourKey);

                    final int m = vehicleHourData.size();
                    for (int i = 0; i < m; i++) {
                        updateNumPassengerInDeadHeadingsMap(hourKey, graphName, -1);
                    }
                }
            }
        }

        vehicleEventsCache.clear();
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

    private int getBucketSize() {
        return (int) Math.ceil(maxPassengersSeenOnGenericCase / 4.0);
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
}

