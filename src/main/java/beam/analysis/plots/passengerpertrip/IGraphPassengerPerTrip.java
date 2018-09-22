package beam.analysis.plots.passengerpertrip;

import beam.analysis.plots.GraphUtils;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface IGraphPassengerPerTrip {

    int CAR_MAX_PASSENGERS = 4;
    int SECONDS_IN_HOUR = 3600;
    int TNC_MAX_PASSENGERS = 6;

    String getFileName(String extension);
    String getTitle();

    String getLegendText(int i);

    void collectEvent(Event event, Map<String, String> attributes);

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

    default List<String> getLegendItemList(int dataSetRowCount) {
        List<String> legendItemList = new ArrayList<>();
        for (int i = 0; i < dataSetRowCount; i++) {
            legendItemList.add(getLegendText(i));
        }
        return legendItemList;
    }

    boolean isValidCase(String graphName, int numPassengers);
}
