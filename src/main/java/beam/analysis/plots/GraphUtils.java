package beam.analysis.plots;

import beam.analysis.plots.modality.RideHailDistanceRowModel;
import org.jfree.chart.*;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.data.category.CategoryDataset;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class GraphUtils {
    private static final List<Color> colors = new ArrayList<>();
    private static final Color DEFAULT_BACK_GROUND = new Color(255, 255, 255);
    /**
     * Map < iteration number, ride hailing revenue>
     */
    public static Map<Integer, RideHailDistanceRowModel> RIDE_HAIL_REVENUE_MAP = new HashMap<>();

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
    }

    public static JFreeChart createStackedBarChartWithDefaultSettings(CategoryDataset dataset, String graphTitle, String xAxisTitle, String yAxisTitle, String fileName, boolean legend) {

        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        final JFreeChart chart = ChartFactory.createStackedBarChart(
                graphTitle, xAxisTitle, yAxisTitle,
                dataset, orientation, legend, toolTips, urls);
        chart.setBackgroundPaint(DEFAULT_BACK_GROUND);
        return chart;
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
            Color color = getBarAndLegendColor(i);
            legendItems.add(new LegendItem(legendItemName.get(i), color));
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
        Color randomColor = new Color(r, g, b);
        return randomColor;
    }
}
