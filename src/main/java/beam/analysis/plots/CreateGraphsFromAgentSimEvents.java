package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import org.jfree.chart.*;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;


/**
 * @Authors asif and rwaraich.
 */
public class CreateGraphsFromAgentSimEvents implements BasicEventHandler {

    private static final int SECONDS_IN_HOUR = 3600;
    public static final List<Color> colors = new ArrayList<>();
    public static final String CAR = "car";
    private static OutputDirectoryHierarchy controlerIO;
    // Static Initializer
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

    private IGraphStats deadHeadingStats = new DeadHeadingStats();
    private IGraphStats fuelUsageStats = new FuelUsageStats();
    private IGraphStats modeChoseStats = new ModeChosenStats();
    private IGraphStats personTravelTimeStats = new PersonTravelTimeStats();

    // No Arg Constructor
    public CreateGraphsFromAgentSimEvents() {
    }

    // Constructor
    public CreateGraphsFromAgentSimEvents(EventsManager eventsManager, OutputDirectoryHierarchy controlerIO, Scenario scenario) {
        eventsManager.addHandler(this);
        this.controlerIO = controlerIO;
        PathTraversalSpatialTemporalTableGenerator.setVehicles(scenario.getTransitVehicles());
    }

    @Override
    public void reset(int iteration) {
        deadHeadingStats.resetStats();
        fuelUsageStats.resetStats();
        modeChoseStats.resetStats();
        personTravelTimeStats.resetStats();
    }

    @Override
    public void handleEvent(Event event) {

        if (event instanceof ModeChoiceEvent || event.getEventType().equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)) {
            modeChoseStats.processStats(event);
        } else if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            fuelUsageStats.processStats(event);
            deadHeadingStats.processStats(event);
        } else if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)) {
            personTravelTimeStats.processStats(event);
        } else if (event instanceof PersonArrivalEvent || event.getEventType().equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE)) {
            personTravelTimeStats.processStats(event);
        }
    }

    public void createGraphs(IterationEndsEvent event) throws IOException {
        modeChoseStats.createGraph(event);
        fuelUsageStats.createGraph(event);
        deadHeadingStats.createGraph(event,"TNC0");
        deadHeadingStats.createGraph(event,"");
        personTravelTimeStats.resetStats();
    }

    public static JFreeChart createStackedBarChart(CategoryDataset dataset,String graphTitle,String xAxisTitle,String yAxisTitle,String fileName,boolean legend){

        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        final JFreeChart chart = ChartFactory.createStackedBarChart(
                graphTitle, xAxisTitle, yAxisTitle,
                dataset, orientation, legend, toolTips, urls);
        chart.setBackgroundPaint(new Color(255, 255, 255));

        return chart;
    }
    public static void processAndPlotLegendItems(CategoryPlot plot,List<String> legendItemName,int dataSetRowCount){
        LegendItemCollection legendItems = new LegendItemCollection();
        for (int i = 0; i < dataSetRowCount; i++) {
            legendItems.add(new LegendItem(legendItemName.get(i), colors.get(i)));
            plot.getRenderer().setSeriesPaint(i, colors.get(i));
        }
        plot.setFixedLegendItems(legendItems);
    }
    public static void processAndPlotLegendItems(CategoryPlot plot,String graphName,int dataSetRowCount,int bucketSize){
        LegendItemCollection legendItems = new LegendItemCollection();
        for (int i = 0; i < dataSetRowCount; i++) {
            Color color = getBarAndLegendColor(i);
            legendItems.add(new LegendItem(getLegendText(graphName, i,bucketSize), color));
            plot.getRenderer().setSeriesPaint(i, colors.get(i));
        }
        plot.setFixedLegendItems(legendItems);
    }
    public static void processAndPlotLegendItems(CategoryPlot plot,int dataSetRowCount){
        LegendItemCollection legendItems = new LegendItemCollection();
        for (int i = 0; i < dataSetRowCount; i++) {
            plot.getRenderer().setSeriesPaint(i, colors.get(i));
        }
        plot.setFixedLegendItems(legendItems);
    }
    public static void saveJFreeChartAsPNG(final JFreeChart chart,int iterationNumber,String fileName) throws IOException{
        int width = 800;
        int height = 600;
        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, fileName);
        ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width, height);
    }
     // helper methods
    public static int getEventHour(double time) {
        return (int) time / SECONDS_IN_HOUR;
    }
    public static String getLegendText(String graphName, int i,int bucketSize) {

        if (graphName.equalsIgnoreCase("car")
                || graphName.equalsIgnoreCase("tnc")
                || graphName.equalsIgnoreCase("tnc_deadheading_distance")
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
    public static Color getBarAndLegendColor(int i) {
        if (i < colors.size()) {
            return colors.get(i);
        } else {
            return getRandomColor();
        }
    }
    public static Color getRandomColor() {
        Random rand = new Random();
        // Java 'Color' class takes 3 floats, from 0 to 1.
        float r = rand.nextFloat();
        float g = rand.nextFloat();
        float b = rand.nextFloat();
        Color randomColor = new Color(r, g, b);
        return randomColor;
    }


}