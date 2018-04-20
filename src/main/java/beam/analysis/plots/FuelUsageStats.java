package beam.analysis.plots;


import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import com.fasterxml.jackson.databind.node.DoubleNode;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.*;

public class FuelUsageStats implements IGraphStats{
    private static Set<String> modesFuel = new TreeSet<>();
    private static Map<Integer, Map<String, Double>> hourModeFuelage = new HashMap<>();
    private static final String graphTitle = "Energy Use by Mode";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Energy Use [MJ]";
    private static final String fileName = "energy_use.png";
    private static Map<Integer, Map<String, List<Double>>> hourModeFuelAverage = new HashMap<>();
    private static Set<String> modesFuelAverage = new TreeSet<>();

    @Override
    public void processStats(Event event) {
        processFuelUsage(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException{
        CategoryDataset modesFuelageDataset = buildModesFuelageGraphDataset();
        createModesFuelageGraph(modesFuelageDataset, event.getIteration());

        CategoryDataset modesFuelAverageDataset = buildModesFuelAverageGraphDataset();
        createModesFuelAverageGraph(modesFuelAverageDataset, event.getIteration());
//        createModesFuelAverageGraph(buildModesFuelAverageGraphDataset(), event.getIteration());
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {

    }

    @Override
    public void resetStats() {
        hourModeFuelage.clear();
        modesFuel.clear();
        hourModeFuelAverage.clear();
    }

    public List<Integer> getSortedHourModeFuelageList(){
        return GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFuelage.keySet());
    }

    public List<Integer> getSortedHourModeFuelAverageList() {
        return GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFuelAverage.keySet());
    }

    public int getFuelageHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour){
        double count = 0;
        double[] modeOccurrencePerHour = getFuelageHourDataAgainstMode(modeChosen,maxHour);
        for(int i =0 ;i < modeOccurrencePerHour.length;i++){
            count=  count+modeOccurrencePerHour[i];
        }
        return (int)Math.ceil(count);
    }
    private double[] getFuelageHourDataAgainstMode(String modeChosen,int maxHour){
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Double> hourData = hourModeFuelage.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }

    private double[] getFuelAverageHourDataAgainstMode(String modeChosen, int maxHour) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, List<Double>> hourData = hourModeFuelAverage.get(hour);
            List<Double> listOfData = (hourData == null) ? new ArrayList<>() : hourData.get(modeChosen);
            double sum = 0;
            double total = 0;
            if (listOfData == null) {
                listOfData = new ArrayList<>();
            }
            for (double entry : listOfData) {
                sum += entry;
                total++;
            }
            total = (total == 0) ? 1 : total;
            modeOccurrencePerHour[index] = (sum / total);
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }

    private CategoryDataset buildModesFuelageGraphDataset() {

        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFuelage.keySet());
        List<String> modesFuelList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesFuel);
        int maxHour = hours.get(hours.size() - 1);
        double[][] dataset = new double[modesFuel.size()][maxHour + 1];
        for (int i = 0; i < modesFuelList.size(); i++) {
            String modeChosen = modesFuelList.get(i);
            dataset[i] = getFuelageHourDataAgainstMode(modeChosen,maxHour);
        }
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    private CategoryDataset buildModesFuelAverageGraphDataset() {

        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFuelAverage.keySet());
        List<String> modesFuelList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesFuelAverage);
        int maxHour = hours.get(hours.size() - 1);
        double[][] dataset = new double[modesFuelAverage.size()][maxHour + 1];
        for (int i = 0; i < modesFuelList.size(); i++) {
            String modeChosen = modesFuelList.get(i);
            dataset[i] = getFuelAverageHourDataAgainstMode(modeChosen, maxHour);
        }
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }
    private void processFuelUsage(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        String vehicleType = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
        String mode = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicleId = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        double lengthInMeters = Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LENGTH));
        String fuelString = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_FUEL);

        modesFuel.add(mode);
        try {
            Double fuel = PathTraversalSpatialTemporalTableGenerator.getFuelConsumptionInMJ(vehicleId, mode, fuelString, lengthInMeters, vehicleType);
            Map<String, Double> hourData = hourModeFuelage.get(hour);
            Map<String, List<Double>> hourDataAverge = hourModeFuelAverage.get(hour);
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

            List internalList;
            if (hourDataAverge == null) {
                hourDataAverge = new HashMap<>();
            }
            if (vehicleId.contains("rideHailingVehicle")) {
                modesFuelAverage.add("RideHail");
                List<Double> rideHailAverageList = hourDataAverge.get("RideHail");
                if (rideHailAverageList == null) {
                    rideHailAverageList = new ArrayList<>();
                }
                rideHailAverageList.add(fuel);
                hourDataAverge.put("RideHail", rideHailAverageList);
                hourModeFuelAverage.put(hour, hourDataAverge);

            } else {
                modesFuelAverage.add("Other");
                List<Double> rideHailAverageList = hourDataAverge.get("Other");
                if (rideHailAverageList == null) {
                    rideHailAverageList = new ArrayList<>();
                }
                rideHailAverageList.add(fuel);
                hourDataAverge.put("Other", rideHailAverageList);

                hourModeFuelAverage.put(hour, hourDataAverge);
            }
//            System.out.println("PATH TRAVERSAL:::" + vehicleType + " " + mode + " " + vehicleId + " " + hour + " final:" + hourModeFuelage.get(hour) + " fuel:" + fuel);

        } catch (Exception e) {
             e.printStackTrace();
        }
    }
    private void createModesFuelageGraph(CategoryDataset dataset, int iterationNumber) throws IOException {

        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);
        GraphUtils.plotLegendItems(plot,modesFuelList,dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }


    private void createModesFuelAverageGraph(CategoryDataset dataset, int iterationNumber) throws IOException {

        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, "Fuel Usage Average by Mode", xAxisTitle, "Average Fuel Usage", "fuelUsageAverage.png", legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuelAverage);
        Collections.sort(modesFuelList);
        GraphUtils.plotLegendItems(plot, modesFuelList, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "fuelUsageAverage.png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }
}
