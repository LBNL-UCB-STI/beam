package beam.analysis.plots;

import beam.analysis.plots.modality.RideHailDistanceRowModel;
import org.jfree.chart.*;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.AxisLocation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.renderer.xy.StandardXYItemRenderer;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.jfree.data.xy.XYDataItem;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class GraphUtils {
    private static final Map<String, Color> colorsForModes = new HashMap<>();
    private static final List<Color> colors = new ArrayList<>();
    private static final Color DEFAULT_BACK_GROUND = new Color(255, 255, 255);
    private static final Color PURPLE = new Color(102,0,153);
    private static final Color LIGHT_BROWN = new Color(153,102,0);
    private static final Color LIGHT_YELLOW = new Color(255, 255,153);
    private static final Color VERY_LIGHT_BLUE = new Color(51, 204, 255);
    private static final Color VERY_LIGHT_RED = new Color(255, 102,102);
    private static final Color VERY_LIGHT_GREEN = new Color(102,255,102);
    private static final Color VERY_DARK_BLUE = new Color(0,0,153);
    private static final Color VERY_DARK_RED = new Color(153,0,0);
    private static final Color VERY_DARK_GREEN = new Color(0,102,0);
    private static final Color OLIVE = new Color(107,142,35);
    private static final Color THISTLE = new Color(216,191,216);
    private static final Color CADETBLUE = new Color(95,158,160);


    /**
     * Map < iteration number, ride hailing revenue>
     */
    public static final Map<Integer, RideHailDistanceRowModel> RIDE_HAIL_REVENUE_MAP = new HashMap<>();

    static {
        colors.add(Color.GREEN);
        colors.add(Color.BLUE);
        colors.add(Color.GRAY);
        colors.add(Color.PINK);
        colors.add(Color.RED);
        colors.add(Color.MAGENTA);
        colors.add(Color.BLACK);
        colors.add(Color.YELLOW);
        colors.add(Color.CYAN);

        colorsForModes.put("car", Color.ORANGE);
        colorsForModes.put("walk", VERY_DARK_RED);
        colorsForModes.put("ride_hail_transit", VERY_DARK_GREEN);
        colorsForModes.put("ride_hail", VERY_LIGHT_RED);
        colorsForModes.put("walk_transit", PURPLE);
        colorsForModes.put("drive_transit", VERY_LIGHT_BLUE);
        colorsForModes.put("subway",LIGHT_BROWN );
        colorsForModes.put("cav", THISTLE);
        colorsForModes.put("bike", OLIVE);
        colorsForModes.put("tram", VERY_LIGHT_GREEN);
        colorsForModes.put("rail", VERY_DARK_BLUE);
        colorsForModes.put("bus", LIGHT_YELLOW);
        colorsForModes.put("ride_hail_pooled", CADETBLUE);

    }

    public static JFreeChart createStackedBarChartWithDefaultSettings(CategoryDataset dataset, String graphTitle, String xAxisTitle, String yAxisTitle, String fileName, boolean legend) {
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        final JFreeChart chart = ChartFactory.createStackedBarChart(
                graphTitle, xAxisTitle, yAxisTitle,
                dataset, orientation, legend, false, false);
        chart.setBackgroundPaint(DEFAULT_BACK_GROUND);
        return chart;
    }

    public static JFreeChart createLineChartWithDefaultSettings(CategoryDataset dataset, String graphTitle, String xAxisTitle, String yAxisTitle, String fileName, boolean legend) {
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        final JFreeChart chart = ChartFactory.createLineChart(
                graphTitle, xAxisTitle, yAxisTitle,
                dataset, orientation, legend, false, false);
        chart.setBackgroundPaint(DEFAULT_BACK_GROUND);
        return chart;
    }

    public static CategoryDataset createCategoryDatasetForIterationsData(String rowKeyPrefix,
                                                        String columnKeyPrefix, double[][] data) {

        DefaultCategoryDataset result = new DefaultCategoryDataset();
        for (int r = 0; r < data.length; r++) {
            String rowKey = rowKeyPrefix + (r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue(new Double(data[r][c]), rowKey, columnKey);
            }
        }
        return result;

    }

    public static XYSeries createXYSeries(String title,String rowKeyPrefix,
                                           String columnKeyPrefix, XYDataItem[] data) {
        XYSeries series = new XYSeries(title);
        for (XYDataItem datum : data) {
            series.add(datum);
        }
        return series;
    }

    public static XYDataset createMultiLineXYDataset(XYSeries[] seriesList) {
        XYSeriesCollection dataset = new XYSeriesCollection();
        for (XYSeries series: seriesList){
            dataset.addSeries(series);
        }
        return dataset;
    }

    public static void setColour(JFreeChart chart, int colorCode) {
        CategoryPlot plot = (CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(SystemColor.inactiveCaption);//change background color

        //set  bar chart color
        ((BarRenderer) plot.getRenderer()).setBarPainter(new StandardBarPainter());

        BarRenderer renderer = (BarRenderer) chart.getCategoryPlot().getRenderer();
        renderer.setSeriesPaint(0, colors.get(colorCode));
    }

    public static void plotLegendItems(CategoryPlot plot, List<String> legendItemName, int dataSetRowCount) {
        LegendItemCollection legendItems = new LegendItemCollection();
        for (int i = 0; i < dataSetRowCount; i++) {
            String legendName = legendItemName.get(i);
            Color color;
            if (colorsForModes.containsKey(legendName)) {
                color = colorsForModes.get(legendName);
            } else {
                color = getBarAndLegendColor(i);   // keeping this for legends other than modes legends
            }
            legendItems.add(new LegendItem(legendName, color));
            plot.getRenderer().setSeriesPaint(i, color);
        }
        plot.setFixedLegendItems(legendItems);
    }

    public static void plotLegendItems(CategoryPlot plot, int dataSetRowCount) {
        LegendItemCollection legendItems = new LegendItemCollection();
        for (int i = 0; i < dataSetRowCount; i++) {
            plot.getRenderer().setSeriesPaint(i, colors.get(i));
        }
        plot.setFixedLegendItems(legendItems);
    }

    public static void saveJFreeChartAsPNG(final JFreeChart chart, String graphImageFile, int width, int height) throws IOException {
        ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width, height);
    }

    private static Color getBarAndLegendColor(int i) {
        if (i < colors.size()) {
            return colors.get(i);
        } else {
            return getRandomColor();
        }
    }

    private static Color getRandomColor() {
        Random rand = new Random();
        // Java 'Color' class takes 3 floats, from 0 to 1.
        float r = rand.nextFloat();
        float g = rand.nextFloat();
        float b = rand.nextFloat();
        return new Color(r, g, b);
    }
}
