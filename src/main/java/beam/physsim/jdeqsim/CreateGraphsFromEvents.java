package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import beam.agentsim.events.ModeChoiceEvent;
import beam.sim.common.GeoUtils;
import org.jfree.chart.*;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;


/**
 * @Authors asif and rwaraich.
 */
public class CreateGraphsFromEvents implements BasicEventHandler {


    public static final String MODE_CAR = "car";
    public static final String MODE_WALK = "walk";
    public static final String MODE_DRIVE_TRANSIT = "drive_transit";
    public static final String MODE_WALK_TRANSIT = "walk_transit";
    public static final String MODE_RIDE_HAILING = "ride_hailing";
    public static final String MODE_BUS = "bus";

    public static final String MODE_CAR_LEGEND = "Car";
    public static final String MODE_WALK_LEGEND = "Walk";
    public static final String MODE_DRIVE_TRANSIT_LEGEND = "Drive Transit";
    public static final String MODE_WALK_TRANSIT_LEGEND = "Walk Transit";
    public static final String MODE_RIDE_HAILING_LEGEND = "Ride Hailing";
    public static final String MODE_BUS_Legend = "Bus";

    public static final Color MODE_CAR_COLOR = Color.blue;
    public static final Color MODE_WALK_COLOR = Color.yellow;
    public static final Color MODE_DRIVE_TRANSIT_COLOR = Color.red;
    public static final Color MODE_WALK_TRANSIT_COLOR = Color.pink;
    public static final Color MODE_RIDE_HAILING_COLOR = Color.green;
    public static final Color MODE_BUS_COLOR = Color.gray;

    public static final String CAR = "car";
    public static final String BUS = "bus";
    public static final String DUMMY_ACTIVITY = "DummyActivity";
    private ActorRef router;
    private OutputDirectoryHierarchy controlerIO;
    private Logger log = LoggerFactory.getLogger(CreateGraphsFromEvents.class);
    private Scenario jdeqSimScenario;
    private PopulationFactory populationFactory;
    private Scenario agentSimScenario;
    private ActorRef registry;

    private ActorRef eventHandlerActorREF;
    private ActorRef jdeqsimActorREF;
    private EventsManager eventsManager;
    private int numberOfLinksRemovedFromRouteAsNonCarModeLinks;

    private Integer writePhysSimEventsInterval;

    private Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();

    public CreateGraphsFromEvents(EventsManager eventsManager, OutputDirectoryHierarchy controlerIO, Scenario scenario, GeoUtils geoUtils, ActorRef registry, ActorRef router, Integer writePhysSimEventsInterval) {
        eventsManager.addHandler(this);
        this.controlerIO = controlerIO;
        this.registry = registry;
        this.router = router;
        agentSimScenario = scenario;

        this.writePhysSimEventsInterval = writePhysSimEventsInterval;
    }

    public CreateGraphsFromEvents() {

    }

    @Override
    public void reset(int iteration) {


    }

    @Override
    public void handleEvent(Event event) {

        if (event instanceof ModeChoiceEvent) {
            addEvent(event);
        }
    }

    private void addEvent(Event event){

        int hour = getEventHour(event.getTime());
        String mode = event.getAttributes().get("mode");

        Map<String, Integer> hourData = hourModeFrequency.get(hour);
        if(hourData == null){

            hourData = new HashMap<>();
            hourData.put(mode, 1);
            hourModeFrequency.put(hour, hourData);
        }else{

            Integer frequency = hourData.get(hour);

            if(frequency == null){
                frequency = 1;
            }else{
                frequency = frequency + 1;
            }

            hourData.put(mode, frequency);
            hourModeFrequency.put(hour, hourData);
        }
    }

    private int getEventHour(double time){

        return (int)time/3600;
    }






    public void createGraph(IterationEndsEvent event){

        CategoryDataset dataset = buildCategoryDataset();
        createGraphs(dataset, event.getIteration());
    }

    private void createGraphs(CategoryDataset dataset, int iterationNumber){

        String plotTitle = "Modes Histogram";
        String xaxis = "Hour";
        String yaxis = "Modes";
        int width = 800;
        int height = 600;
        boolean show = true;
        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, "hour_mode_frequencies.png");

        final JFreeChart chart = ChartFactory.createStackedBarChart(
                plotTitle , xaxis, yaxis,
                dataset, orientation, show, toolTips, urls);

        chart.setBackgroundPaint(new Color(255, 255, 255));
        CategoryPlot plot = chart.getCategoryPlot();

        LegendItemCollection legendItems = new LegendItemCollection();
        legendItems.add(new LegendItem(MODE_CAR_LEGEND, MODE_CAR_COLOR));
        legendItems.add(new LegendItem(MODE_WALK_LEGEND, MODE_WALK_COLOR));
        legendItems.add(new LegendItem(MODE_DRIVE_TRANSIT_LEGEND, MODE_DRIVE_TRANSIT_COLOR));
        legendItems.add(new LegendItem(MODE_WALK_TRANSIT_LEGEND, MODE_WALK_TRANSIT_COLOR));
        legendItems.add(new LegendItem(MODE_RIDE_HAILING_LEGEND, MODE_RIDE_HAILING_COLOR));
        legendItems.add(new LegendItem(MODE_RIDE_HAILING_LEGEND, MODE_BUS_COLOR));
        plot.setFixedLegendItems(legendItems);

        plot.getRenderer().setSeriesPaint(0, MODE_CAR_COLOR);
        plot.getRenderer().setSeriesPaint(1, MODE_WALK_COLOR);
        plot.getRenderer().setSeriesPaint(2, MODE_DRIVE_TRANSIT_COLOR);
        plot.getRenderer().setSeriesPaint(3, MODE_WALK_TRANSIT_COLOR);
        plot.getRenderer().setSeriesPaint(4, MODE_RIDE_HAILING_COLOR);
        plot.getRenderer().setSeriesPaint(5, MODE_BUS_COLOR);

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }

    private CategoryDataset buildCategoryDataset(){

        double[][] dataset = new double[6][hourModeFrequency.keySet().size()];


        java.util.List<Integer> keyList = new ArrayList<>();
        keyList.addAll(hourModeFrequency.keySet());
        Collections.sort(keyList);

        double[] carFrequency = new double[hourModeFrequency.keySet().size()];
        double[] walkFrequency = new double[hourModeFrequency.keySet().size()];
        double[] driveTransitFrequency = new double[hourModeFrequency.keySet().size()];
        double[] walkTransitFrequency = new double[hourModeFrequency.keySet().size()];
        double[] rideFrequency = new double[hourModeFrequency.keySet().size()];
        double[] busFrequency = new double[hourModeFrequency.keySet().size()];

        int index = 0;
        for(int hour : keyList){

            Map<String, Integer> hourData = hourModeFrequency.get(hour);

            carFrequency[index] = hourData.get(MODE_CAR) == null ? 0 : hourData.get(MODE_CAR);
            walkFrequency[index] = hourData.get(MODE_WALK) == null ? 0 : hourData.get(MODE_WALK);
            driveTransitFrequency[index] = hourData.get(MODE_DRIVE_TRANSIT) == null ? 0 : hourData.get(MODE_DRIVE_TRANSIT);
            walkTransitFrequency[index] = hourData.get(MODE_WALK_TRANSIT) == null ? 0 : hourData.get(MODE_WALK_TRANSIT);
            rideFrequency[index] = hourData.get(MODE_RIDE_HAILING) == null ? 0 : hourData.get(MODE_RIDE_HAILING);
            busFrequency[index] = hourData.get(MODE_BUS) == null ? 0 : hourData.get(MODE_BUS);

            index = index + 1;
        }

        dataset[0] = carFrequency;
        dataset[1] = walkFrequency;
        dataset[2] = driveTransitFrequency;
        dataset[3] = walkTransitFrequency;
        dataset[4] = rideFrequency;
        dataset[5] = busFrequency;

        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }
}

