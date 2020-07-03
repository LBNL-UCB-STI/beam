package beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.lang.ArrayUtils;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

class CaccRoadCapacityGraphs {
    private static final Logger log = LoggerFactory.getLogger(CaccRoadCapacityGraphs.class);
    /**
     * A scattered plot that analyses the percentage of increase of road capacity observed for a given fraction of CACC enabled travelling on
     * CACC enabled roads
     *
     * @param caccCapacityIncrease data map for the graph
     * @param graphImageFile       output graph file name
     */
    static void generateCapacityIncreaseScatterPlotGraph(MultiValuedMap<Double, Double> caccCapacityIncrease, String graphImageFile) {
        String plotTitle = "CACC - Road Capacity Increase";
        String x_axis = "CACC on Road (%)";
        String y_axis = "Road Capacity Increase (%)";
        int width = 1000;
        int height = 600;

        XYSeriesCollection dataset = new XYSeriesCollection();
        XYSeries series = new XYSeries("cacc", false);
        caccCapacityIncrease.entries().forEach(e -> series.add(e.getKey(), e.getValue()));
        dataset.addSeries(series);

        JFreeChart chart = ChartFactory.createScatterPlot(
                plotTitle,
                x_axis, y_axis, dataset);

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    /**
     * A histogram graph that chart+
     * s the frequencies of CACC enabled road percentage increase observed in a simulation
     *
     * @param capacityIncreaseFrequencies data map for the graph
     * @param graphImageFile              output graph file name
     */
    static void generateCapacityIncreaseHistogramGraph(Map<String, Double> capacityIncreaseFrequencies,
            String graphImageFile, String plotTitle) {
        String x_axis = "Road Capacity Increase (%)";
        String y_axis = "Frequency";
        int width = 1000;
        int height = 600;

        Double[] value = capacityIncreaseFrequencies.values().toArray(new Double[0]);
        int number = 20;
        HistogramDataset dataset = new HistogramDataset();
        dataset.setType(HistogramType.FREQUENCY);
        dataset.addSeries("Road Capacity", ArrayUtils.toPrimitive(value), number, 0.0, 100.0);

        JFreeChart chart = ChartFactory.createHistogram(
                plotTitle,
                x_axis, y_axis, dataset, PlotOrientation.VERTICAL, false, true, true);

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

}
