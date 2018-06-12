package beam.analysis.plots;


import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.via.CSVWriter;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;

public class FuelUsageStats implements IGraphStats{
    private static Set<String> modesFuel = new TreeSet<>();
    private static Map<Integer, Map<String, Double>> hourModeFuelage = new HashMap<>();
    private static final String graphTitle = "Energy Use by Mode";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Energy Use [MJ]";
    private static final String fileName = "energy_use.png";


    @Override
    public void processStats(Event event) {
        processFuelUsage(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException{
        CategoryDataset modesFuelageDataset = buildModesFuelageGraphDataset();
        createModesFuelageGraph(modesFuelageDataset, event.getIteration());
        createFuelCSV(hourModeFuelage, event.getIteration());
    }



    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {

    }

    @Override
    public void resetStats() {
        hourModeFuelage.clear();
        modesFuel.clear();
    }

    public List<Integer> getSortedHourModeFuelageList(){
        return GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFuelage.keySet());
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
    private void processFuelUsage(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        Map<String, String> eventAttributes = event.getAttributes();
        String vehicleType = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
        String originalMode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicleId = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        double lengthInMeters = Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH));
        String fuelString = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_FUEL);

        String mode = originalMode;
        if (mode.equalsIgnoreCase("car") && vehicleId.contains("rideHailingVehicle")) {
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

    private void createFuelCSV(Map<Integer, Map<String, Double>> hourModeFuelage, int iterationNumber) {

        String SEPERATOR=",";

        CSVWriter csvWriter = new CSVWriter(GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "energy_use.csv"));
        BufferedWriter bufferedWriter = csvWriter.getBufferedWriter();


        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFuelage.keySet());
        List<String> modesFuelList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesFuel);

        int maxHour = hours.get(hours.size() - 1);
        //double[][] dataset = new double[modesFuel.size()][maxHour + 1];

        try {


            bufferedWriter.append("Modes");
            bufferedWriter.append(SEPERATOR);
            for(int j = 0; j < maxHour; j++){
                bufferedWriter.append("Bin_" + String.valueOf(j));
                bufferedWriter.append(SEPERATOR);
            }
            bufferedWriter.append("\n");

            for (int i = 0; i < modesFuelList.size(); i++) {
                String modeChosen = modesFuelList.get(i);
                //dataset[i] = getFuelageHourDataAgainstMode(modeChosen,maxHour);

                bufferedWriter.append(modeChosen);
                bufferedWriter.append(SEPERATOR);

                for(int j = 0; j < maxHour; j++){
                    Map<String, Double> modesData = hourModeFuelage.get(j);


                    String modeHourValue = "0";

                    if(modesData != null) {
                        if (modesData.get(modeChosen) != null) {
                            modeHourValue = modesData.get(modeChosen).toString();
                        }
                    }

                    bufferedWriter.append(modeHourValue.toString());
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
