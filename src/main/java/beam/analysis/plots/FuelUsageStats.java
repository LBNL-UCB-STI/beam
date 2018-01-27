package beam.analysis.plots;


import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
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
    public List<Integer> getSortedHourModeFuelageList(){
        List<Integer> hours = new ArrayList<>();
        hours.addAll(hourModeFuelage.keySet());
        Collections.sort(hours);
        return hours;
    }
    public int getFuelageHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour){
        double count = 0;
        double[] modeOccurrencePerHour = getFuelageHourDataAgainstMode(modeChosen,maxHour);
        for(int i =0 ;i < modeOccurrencePerHour.length;i++){
            count=  count+modeOccurrencePerHour[i];
        }
        return (int)Math.ceil(count);
    }
    private List<String> getSortedModeFuleList(){
        List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);
        return modesFuelList;
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

        List<Integer> hours = getSortedHourModeFuelageList();
        List<String> modesFuelList = getSortedModeFuleList();
        int maxHour = hours.get(hours.size() - 1);
        double[][] dataset = new double[modesFuel.size()][maxHour + 1];
        for (int i = 0; i < modesFuelList.size(); i++) {
            String modeChosen = modesFuelList.get(i);
            dataset[i] = getFuelageHourDataAgainstMode(modeChosen,maxHour);
        }
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }
    private void processFuelUsage(Event event) {

        int hour = CreateGraphsFromAgentSimEvents.getEventHour(event.getTime());


        String vehicleType = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
        String mode = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicleId = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        double lengthInMeters = Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LENGTH));
        String fuelString = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_FUEL);

        modesFuel.add(mode);

        try {

            Double fuel = PathTraversalSpatialTemporalTableGenerator.getFuelConsumptionInMJ(vehicleId, mode, fuelString, lengthInMeters, vehicleType);
            ;

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

        String graphTitle = "Energy Use by Mode";
        String xAxisTitle = "Hour";
        String yAxisTitle = "Energy Use [MJ]";
        String fileName = "energy_use.png";

        boolean legend = true;
        final JFreeChart chart = CreateGraphsFromAgentSimEvents.createStackedBarChart(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);
        CreateGraphsFromAgentSimEvents.processAndPlotLegendItems(plot,modesFuelList,dataset.getRowCount());
        CreateGraphsFromAgentSimEvents.saveJFreeChartAsPNG(chart,iterationNumber,fileName);
    }

    @Override
    public void processStats(Event event) {
        processFuelUsage(event);
    }
    @Override
    public void createGraph(IterationEndsEvent event) throws IOException{
        CategoryDataset modesFuelageDataset = buildModesFuelageGraphDataset();
        createModesFuelageGraph(modesFuelageDataset, event.getIteration());
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {

    }

    @Override
    public void resetStats() {
        hourModeFuelage.clear();
        modesFuel.clear();
    }
}
