package beam.analysis.plots;

import beam.sim.metrics.MetricsSupport;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public abstract class BaseModeAnalysis<T extends Map<Integer, Map>> implements GraphAnalysis, MetricsSupport {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    protected CategoryDataset createReferenceCategoryDataset(String columnKeyPrefix, double[][] data, Map<String, Double> benchMarkData) {
        DefaultCategoryDataset result = new DefaultCategoryDataset();
        List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(benchMarkData.keySet());
        double sum = benchMarkData.values().stream().reduce((x, y) -> x + y).orElse(0.0);
        for (int i = 0; i < modesChosenList.size(); i++) {
            String rowKey = String.valueOf(i + 1);
            result.addValue((benchMarkData.get(modesChosenList.get(i)) * 100) / sum, rowKey, "benchmark");
        }
        int max = 0;
        for (double[] aData : data) {
            if (aData.length > max) {
                max = aData.length;
            }
        }
        double[] sumOfColumns = new double[max];
        for (double[] aData : data) {
            for (int c = 0; c < aData.length; c++) {
                sumOfColumns[c] += aData[c];
            }
        }

        for (int r = 0; r < data.length; r++) {
            String rowKey = String.valueOf(r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue((data[r][c] * 100) / sumOfColumns[c], rowKey, columnKey);
            }
        }
        return result;
    }

    protected CategoryDataset createCategoryDataset(String columnKeyPrefix, double[][] data) {
        DefaultCategoryDataset result = new DefaultCategoryDataset();
        for (int r = 0; r < data.length; r++) {
            String rowKey = String.valueOf(r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue(data[r][c], rowKey, columnKey);
            }
        }
        return result;
    }

    protected void createGraphInRootDirectory(CategoryDataset dataset, String graphTitleName, String fileName, String xAxisTitle, String yAxisTitle, Set<String> modes) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitleName, xAxisTitle, yAxisTitle, fileName, true);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(modes);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    // csv for root graph
    public void writeToRootCSV(String fileName, T modeIteration, Set<String> modes) {
        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(fileName)))) {
            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            int max = modeIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            for (int iteration = 0; iteration <= max; iteration++) {
                T modeCountIteration = (T) modeIteration.get(iteration);
                StringBuilder stringBuilder = new StringBuilder(iteration + "");
                if (modeCountIteration != null) {
                    for (String mode : modes) {
                        if (modeCountIteration.get(mode) != null) {
                            stringBuilder.append(",").append(modeCountIteration.get(mode));
                        } else {
                            stringBuilder.append(",0");
                        }
                    }
                } else {
                    for (String ignored : modes) {
                        stringBuilder.append(",0");
                    }
                }
                out.write(stringBuilder.toString());
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("error in generating CSV", e);
        }
    }
}
