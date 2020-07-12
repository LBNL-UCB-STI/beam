package beam.analysis.plots.passengerpertrip;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.GraphUtils;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import com.google.common.base.CaseFormat;
import org.jfree.data.category.CategoryDataset;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class GenericPassengerPerTrip implements IGraphPassengerPerTrip{
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# trips";
    private static final int DEFAULT_OCCURRENCE = 1;
    int eventCounter = 0;
    int maxHour = 0;

    final String graphName;


    final Map<Integer, Map<Integer, Integer>> numPassengerToEventFrequencyBin = new HashMap<>();

    // Specific to TncPassengerPerTrip
    private final Map<String, Map<Integer, Map<Integer, Integer>>> deadHeadingsMap = new HashMap<>();

    int maxPassengersSeenOnGenericCase = 0;

    public GenericPassengerPerTrip(String graphName){
        this.graphName = graphName;
    }

    @Override
    public void collectEvent(PathTraversalEvent event) {
        eventCounter++;

        int hour = getEventHour(event.getTime());
        maxHour = maxHour < hour ? hour : maxHour;

        Integer _num_passengers = event.numberOfPassengers();
        maxPassengersSeenOnGenericCase = maxPassengersSeenOnGenericCase < _num_passengers ? _num_passengers : maxPassengersSeenOnGenericCase;

        updateNumPassengerInDeadHeadingsMap(hour, graphName, _num_passengers);
    }

    @Override
    public void process(IterationEndsEvent event) throws IOException {
        double[][] matrixDataSet = buildDeadHeadingDataSet(deadHeadingsMap.get(graphName), graphName);

        CategoryDataset dataSet = GraphUtils.createCategoryDataset("Mode ", "", matrixDataSet);
        draw(dataSet, event.getIteration(), xAxisTitle, yAxisTitle, event.getServices().getControlerIO());

        writeCSV(matrixDataSet, event.getIteration(), event.getServices().getControlerIO());
    }

    private double[][] buildDeadHeadingDataSet(Map<Integer, Map<Integer, Integer>> data, String graphName) {
        int maxPassengers = maxPassengersSeenOnGenericCase;
        double[][] dataSet;

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

        return dataSet;
    }


    @Override
    public String getFileName(String extension) {
        return "passengerPerTrip" + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, graphName) + "." + extension;
    }

    @Override
    public String getTitle() {
        return "Number of Passengers per Trip [" + graphName.toUpperCase() + "]";
    }

    @Override
    public String getLegendText(int i) {

        int bucketSize = getBucketSize();

        if (i == 0) {
            return "0";
        } else {
            int start = (i - 1) * bucketSize + 1;
            int end = (i - 1) * bucketSize + bucketSize;
            return start + "-" + end;
        }

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

    private double[] getEventFrequenciesBinByNumberOfPassengers(int numberOfpassengers, int maxHour) {
        Map<Integer, Integer> eventFrequenciesBin = numPassengerToEventFrequencyBin.get(numberOfpassengers);

        double[] data = new double[maxHour + 1];

        if(eventFrequenciesBin != null){

            for(int i = 0; i < maxHour + 1; i++){
                Integer frequency = eventFrequenciesBin.get(i);
                if(frequency == null){
                    data[i] = 0;
                }else{
                    data[i] = frequency;
                }
            }
        }

        return data;
    }

    @Override
    public boolean isValidCase(String graphName, int numPassengers) {
        return numPassengers <= TNC_MAX_PASSENGERS;
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
}

