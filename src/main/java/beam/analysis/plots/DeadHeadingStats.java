package beam.analysis.plots;


import beam.agentsim.events.PathTraversalEvent;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;
import sun.net.www.content.image.png;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class DeadHeadingStats implements IGraphStats {
    private static final Integer TNC_MAX_PASSENGERS = 6;
    private static final Integer CAR_MAX_PASSENGERS = 4;
    private static final int METERS_IN_KM = 1000;
    private static final String deadHeadingTNC0XAxisTitle = "Hour";
    private static final String deadHeadingTNC0YAxisTitle = "Distance in kilometers";
    private static final String deadHeadingXAxisTitle = "Hour";
    private static final String deadHeadingYAxisTitle = "# trips";
    private static Map<String, Map<Integer, Map<Integer, Integer>>> deadHeadingsMap = new HashMap<>();
    private static Map<Integer, Map<Integer, Double>> deadHeadingsTnc0Map = new HashMap<>();
    private static int maxPassengersSeenOnGenericCase = 0;
    private String fileNameBase = "rideHail";
    private String graphTitle = null;
    private static final int DEFAULT_OCCURRENCE=1;

    @Override
    public void processStats(Event event) {
        processDeadHeading(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {

    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {
        if (graphType.equalsIgnoreCase("TNC0")) {
            CategoryDataset tnc0DeadHeadingDataset = buildDeadHeadingDatasetTnc0ForGraph();
            createDeadHeadingGraphTnc0(tnc0DeadHeadingDataset, event.getIteration(), GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE);
            writeToCSV(event.getIteration(), GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE);
        } else {
            List<String> graphNamesList = GraphsStatsAgentSimEventsListener.getSortedStringList(deadHeadingsMap.keySet());
            for (String graphName : graphNamesList) {
                CategoryDataset tncDeadHeadingDataset = buildDeadHeadingDataForGraph(graphName);
                createDeadHeadingGraph(tncDeadHeadingDataset, event.getIteration(), graphName);
            }
        }
    }
    @Override
    public void resetStats() {
        deadHeadingsMap.clear();
        deadHeadingsTnc0Map.clear();
        maxPassengersSeenOnGenericCase = 0;
    }

    public int getDeadHeadingTnc0HourDataCount(int hourIndex) {
        double[][] dataset = buildDeadHeadingDatasetTnc0();
        double[] hoursData = dataset[hourIndex];
        double count = 0;
        for (double hourData : hoursData) {
            count = count + hourData;
        }
        return (int) Math.ceil(count);
    }

    public int getDeadHeadingTnc0HourDataCount(int hourIndex, int hour) {
        double[][] dataset = buildDeadHeadingDatasetTnc0();
        double[] hoursData = dataset[hourIndex];
        return (int) Math.ceil(hoursData[hour]);
    }

    public int getBucketCountAgainstMode(int bucketIndex, String mode) {
        double[][] dataset = buildDeadHeadingDataset(this.deadHeadingsMap.get(mode), mode);
        double[] hoursData = dataset[bucketIndex];
        double count = 0;
        for (double hourData : hoursData) {
            count = count + hourData;
        }
        return (int) Math.ceil(count);
    }

    public int getPassengerPerTripCountForSpecificHour(int bucketIndex, String mode, int hour) {
        double[][] dataset = buildDeadHeadingDataset(this.deadHeadingsMap.get(mode), mode);
        double[] hoursData = dataset[bucketIndex];
        return (int) Math.ceil(hoursData[hour]);
    }
    private void processDeadHeading(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        Map<String, String> eventAttributes = event.getAttributes();
        String mode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicle_id = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        String graphName = getGraphNameAgainstModeAndVehicleId(mode,vehicle_id);
        Integer _num_passengers = getPathTraversalEventNumOfPassengers(event, eventAttributes);
        boolean validCase = isValidCase(graphName, _num_passengers);
        if (validCase) {
            updateNumPassengerInDeadHeadingsMap(hour,graphName,_num_passengers);
        }

        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)) {
            Double length = Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH));
            updateDeadHeadingTNCMap(length,hour,_num_passengers);
        }
    }
    private boolean updateDeadHeadingTNCMap(double length,int hour,Integer _num_passengers){
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
        return true;
    }
    private Integer getPathTraversalEventNumOfPassengers(Event event, Map<String, String> eventAttributes){
        String num_passengers = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS);
        Integer _num_passengers =null;
        try {
            _num_passengers = Integer.parseInt(num_passengers);
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
        }
        return _num_passengers;
    }
    public boolean updateNumPassengerInDeadHeadingsMap(int hour,String graphName,Integer _num_passengers){

        Map<Integer, Map<Integer, Integer>> deadHeadings = deadHeadingsMap.get(graphName);
        Map<Integer, Integer> hourData = null;
        if (deadHeadings != null)
            hourData = deadHeadings.get(hour);
        else{
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
        return true;
    }

    private String getGraphNameAgainstModeAndVehicleId(String mode,String vehicle_id){
        String graphName = mode;
        if (mode.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR) && vehicle_id.contains(GraphsStatsAgentSimEventsListener.RIDE)) {
            graphName = GraphsStatsAgentSimEventsListener.TNC;
        }
        return graphName;
    }

    private void createDeadHeadingGraphTnc0(CategoryDataset dataset, int iterationNumber, String graphName) throws IOException {
        String fileName = getFileName(graphName,"png");
        String graphTitle = getTitle(graphName);
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, deadHeadingTNC0XAxisTitle, deadHeadingTNC0YAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> legendItemList= getLegendItemList(graphName,dataset.getRowCount(),getBucketSize());
        GraphUtils.plotLegendItems(plot,legendItemList,dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void createDeadHeadingGraph(CategoryDataset dataset, int iterationNumber, String graphName) throws IOException {
        String fileName = getFileName(graphName,"png");
        String graphTitle = getTitle(graphName);
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, deadHeadingXAxisTitle, deadHeadingYAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> legendItemList= getLegendItemList(graphName,dataset.getRowCount(),getBucketSize());
        GraphUtils.plotLegendItems(plot,legendItemList,dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }
    private List<String> getLegendItemList(String graphName,int dataSetRowCount,int bucketSize){
        List<String> legendItemList = new ArrayList<>();
        for (int i = 0; i < dataSetRowCount; i++) {
            legendItemList.add(getLegendText(graphName, i,bucketSize));
        }
        return legendItemList;
    }
    private static String getLegendText(String graphName, int i,int bucketSize) {

        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR)
                || graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)
                || graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE)
                ) {
            return Integer.toString(i);
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

    private double[] getDeadHeadingDatasetTnc0ModeOccurrencePerHour(int maxHour, int outerLoopIndex) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        //String passengerCount = "p" + i;
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<Integer, Double> hourData = deadHeadingsTnc0Map.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(outerLoopIndex) == null ? 0 : hourData.get(outerLoopIndex) / METERS_IN_KM;
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }

    private double[][] buildDeadHeadingDatasetTnc0() {
        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(deadHeadingsTnc0Map.keySet());
        int maxHour = 0;
        if (hours.size() > 0) {
            maxHour = hours.get(hours.size() - 1);
        }
        Integer maxPassengers = TNC_MAX_PASSENGERS;
        double dataset[][] = new double[maxPassengers + 1][maxHour + 1];
        for (int i = 0; i <= maxPassengers; i++) {
            dataset[i] = getDeadHeadingDatasetTnc0ModeOccurrencePerHour(maxHour, i);
        }
        return dataset;
    }

    private CategoryDataset buildDeadHeadingDatasetTnc0ForGraph() {

        double[][] dataset = buildDeadHeadingDatasetTnc0();
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    private double[] getModeOccurrencePerHourAgainstMode(Map<Integer, Map<Integer, Integer>> data, int maxHour, int outerLoopIndex) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        //String passengerCount = "p" + i;
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

    private double[][] buildDeadHeadingDataset(Map<Integer, Map<Integer, Integer>> data, String graphName) {
        List<Integer> hours = new ArrayList<>();
        hours.addAll(data.keySet());
        Collections.sort(hours);
        int maxHour = hours.get(hours.size() - 1);
        Integer maxPassengers = null;
        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR)) {
            maxPassengers = CAR_MAX_PASSENGERS;
        } else if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)) {
            maxPassengers = TNC_MAX_PASSENGERS;
        } else {
            maxPassengers = maxPassengersSeenOnGenericCase;
        }
        double dataset[][] = null;
        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC) || graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.CAR)) {
            dataset = new double[maxPassengers + 1][maxHour + 1];
            for (int i = 0; i <= maxPassengers; i++) {
                dataset[i] = getModeOccurrencePerHourAgainstMode(data, maxHour, i);
            }
        } else {

            // This loop gives the loop over all the different passenger groups, which is 1 in other cases.
            // In this case we have to group 0, 1 to 5, 6 to 10

            int bucketSize = getBucketSize();
            dataset = new double[5][maxHour + 1];
            // We need only 5 buckets
            // The modeOccurrentPerHour array index will not go beyond 5 as all the passengers will be
            // accomodated within the 4 buckets because the index will not be incremented until all
            // passengers falling in one bucket are added into that index of modeOccurrencePerHour
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            int bucket = 0;
            for (int i = 0; i <= maxPassengers; i++) {
                modeOccurrencePerHour = getModeOccurrenceOfPassengerWithBucketSize(data, modeOccurrencePerHour, maxHour, i);
                if (i == 0 || (i % bucketSize == 0) || i == maxPassengers) {
                    dataset[bucket] = modeOccurrencePerHour;
                    modeOccurrencePerHour = new double[maxHour + 1];
                    bucket = bucket + 1;
                }
            }
        }
        return dataset;
    }

    private CategoryDataset buildDeadHeadingDataForGraph(String mode) {
        double[][] dataset = buildDeadHeadingDataset(this.deadHeadingsMap.get(mode), mode);
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
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
                if (maxPassengersSeenOnGenericCase < numPassengers) maxPassengersSeenOnGenericCase = numPassengers;
                validCase = true;
            }
        }
        return validCase;
    }

    private String getFileName(String graphName,String extension) {
        if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC)) {
            return fileNameBase+"PassengersPerTrip."+extension;
        } else if (graphName.equalsIgnoreCase(GraphsStatsAgentSimEventsListener.TNC_DEAD_HEADING_DISTANCE)) {
            return fileNameBase+"DeadheadDistance."+extension;
        } else {
            return fileNameBase+"PassengersPerTrip."+extension;
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
    private void writeToCSV(int iterationNumber, String graphName) throws IOException {
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, getFileName(graphName,"csv"));
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(new File(csvFileName)));
            String heading = "hour,numPassengers,vkt";
            out.write(heading);
            out.newLine();

            HashSet<Integer> passengerCategories = new HashSet<Integer>();
            int maxHour = Integer.MIN_VALUE;
            int minHour = Integer.MAX_VALUE;
            Iterator iter = deadHeadingsTnc0Map.keySet().iterator();
            while(iter.hasNext()){
                int nextHour = (Integer)iter.next();
                if(nextHour>maxHour)maxHour=nextHour;
                if(nextHour<minHour)minHour=nextHour;
                Iterator keyIter = ((Map<Integer,Double>)deadHeadingsTnc0Map.get(nextHour)).keySet().iterator();
                while(keyIter.hasNext()){
                    passengerCategories.add((Integer)keyIter.next());
                }
            }

            Double vkt = null;
            for (Integer hour = minHour; hour <= maxHour; hour++) {
                Iterator keyIter = passengerCategories.iterator();
                while(keyIter.hasNext()){
                    Integer passengerKey = (Integer)keyIter.next();
                    if(deadHeadingsTnc0Map.containsKey(hour)) {
                        if (((Map<Integer, Double>) deadHeadingsTnc0Map.get(hour)).containsKey(passengerKey)) {
                            vkt = ((Map<Integer, Double>) deadHeadingsTnc0Map.get(hour)).get(passengerKey);
                        } else {
                            vkt = 0.0;
                        }
                    }else {
                        vkt = 0.0;
                    }
                    out.write(hour.toString()+","+passengerKey.toString()+","+vkt.toString());
                    out.newLine();
                }
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }
}
