package beam.analysis.plots.passengerpertrip;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.GraphUtils;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public interface IGraphPassengerPerTrip {

    int CAR_MAX_PASSENGERS = 4;
    int SECONDS_IN_HOUR = 3600;
    int TNC_MAX_PASSENGERS = 6;

    String getFileName(String extension);
    String getTitle();

    String getLegendText(int i);

    void collectEvent(PathTraversalEvent event);

    void process(IterationEndsEvent event) throws IOException;
    CategoryDataset getCategoryDataSet();

    default int getEventHour(double time) {
        return (int) time / SECONDS_IN_HOUR;
    }

    default void draw(CategoryDataset dataSet, int iterationNumber, String xAxisTitle, String yAxisTitle) throws IOException {

        String fileName = getFileName("png");
        String graphTitle = getTitle();
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataSet, graphTitle, xAxisTitle, yAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> legendItemList = getLegendItemList(dataSet.getRowCount());
        GraphUtils.plotLegendItems(plot, legendItemList, dataSet.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }


    default void writeCSV(double[][] dataMatrix,int rowCount, int iterationNumber) {
        String fileName = getFileName("csv");
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        try(final BufferedWriter writer = new BufferedWriter(new FileWriter(csvFileName))) {
            List<String> legendItemList = getLegendItemList(rowCount);
            writer.write("hours");
            for(String headerToken : legendItemList){
                writer.write(","+headerToken);
            }
            writer.write("\n");
            Map<Integer, String> hoursValue = new TreeMap<>();

            for (double[] data : dataMatrix){
                int hour = 1;
                for(double hourData : data){
                    hoursValue.merge(hour++, ","+hourData, String::concat);
                }
            }
            Set<Integer> hours = hoursValue.keySet();
            for(Integer hour : hours){
                writer.write(hour+hoursValue.get(hour)+"\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    default List<String> getLegendItemList(int dataSetRowCount) {
        List<String> legendItemList = new ArrayList<>();
        for (int i = 0; i < dataSetRowCount; i++) {
            legendItemList.add(getLegendText(i));
        }
        return legendItemList;
    }

    boolean isValidCase(String graphName, int numPassengers);
}
