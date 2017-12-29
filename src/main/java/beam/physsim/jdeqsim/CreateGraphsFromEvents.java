package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
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
    public static final String MODE_BUS_LEGEND = "Bus";

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
    private Map<Integer, Map<String, Double>> hourModeFuelage = new HashMap<>();
    private Map<Integer, Map<Integer, Integer>> carDeadHeadings = new HashMap<>();
    private Map<Integer, Map<Integer, Integer>> busDeadHeadings = new HashMap<>();

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

            addModeChoiceEvent(event);
        }else if(event instanceof PathTraversalEvent){

            addModeFuelage((PathTraversalEvent)event);

            processDeadHeading((PathTraversalEvent)event);
        }
    }

    private void addModeChoiceEvent(Event event){

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



    ///
    private void addModeFuelage(PathTraversalEvent event){

        int hour = getEventHour(event.getTime());
        String mode = event.getAttributes().get("mode");

        String fuel = event.getAttributes().get("fuel");

        if(fuel != null && !fuel.equalsIgnoreCase("NA")) {

            try{

                Double _fuel = Double.parseDouble(fuel);

                Map<String, Double> hourData = hourModeFuelage.get(hour);
                if (hourData == null) {

                    hourData = new HashMap<>();
                    hourData.put(mode, _fuel);
                    hourModeFuelage.put(hour, hourData);
                } else {

                    Double fuelage = hourData.get(hour);

                    if (fuelage == null) {
                        fuelage = _fuel;
                    } else {
                        fuelage = fuelage + _fuel;
                    }

                    hourData.put(mode, fuelage);
                    hourModeFuelage.put(hour, hourData);
                }
            }catch (Exception e){

                e.printStackTrace();
            }
        }
    }
    //

    //




    public void createGraphs(IterationEndsEvent event){

        CategoryDataset modesFrequencyDataset = buildModesFrequencyDataset();
        createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        CategoryDataset modesFuelageDataset = buildModesFuelageDataset();
        createModesFuelageGraph(modesFuelageDataset, event.getIteration());

        CategoryDataset carDeadHeadingDataset = buildDeadHeadingDataset(carDeadHeadings);
        createDeadHeadingGraph(carDeadHeadingDataset, event.getIteration(), "car");

        CategoryDataset busDeadHeadingDataset = buildDeadHeadingDataset(carDeadHeadings);
        createDeadHeadingGraph(busDeadHeadingDataset, event.getIteration(), "bus");
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber){

        String plotTitle = "Mode Choice Histogram";
        String xaxis = "Hour";
        String yaxis = "# mode chosen";
        int width = 800;
        int height = 600;
        boolean show = true;
        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, "mode_chosen.png");

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
        legendItems.add(new LegendItem(MODE_BUS_LEGEND, MODE_BUS_COLOR));
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

    private CategoryDataset buildModesFrequencyDataset(){

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

    ////
    private void createModesFuelageGraph(CategoryDataset dataset, int iterationNumber){

        String plotTitle = "Energy Use by Mode";
        String xaxis = "Hour";
        String yaxis = "Energy Use [?]";
        int width = 800;
        int height = 600;
        boolean show = true;
        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, "energy_use.png");

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
        legendItems.add(new LegendItem(MODE_BUS_LEGEND, MODE_BUS_COLOR));
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

    private CategoryDataset buildModesFuelageDataset(){

        double[][] dataset = new double[6][hourModeFuelage.keySet().size()];


        java.util.List<Integer> keyList = new ArrayList<>();
        keyList.addAll(hourModeFuelage.keySet());
        Collections.sort(keyList);

        double[] carFuelage = new double[hourModeFuelage.keySet().size()];
        double[] walkFuelage = new double[hourModeFuelage.keySet().size()];
        double[] driveTransitFuelage = new double[hourModeFuelage.keySet().size()];
        double[] walkTransitFuelage = new double[hourModeFuelage.keySet().size()];
        double[] rideFuelage = new double[hourModeFuelage.keySet().size()];
        double[] busFuelage = new double[hourModeFuelage.keySet().size()];

        int index = 0;
        for(int hour : keyList){

            Map<String, Double> hourData = hourModeFuelage.get(hour);

            carFuelage[index] = hourData.get(MODE_CAR) == null ? 0 : hourData.get(MODE_CAR);
            walkFuelage[index] = hourData.get(MODE_WALK) == null ? 0 : hourData.get(MODE_WALK);
            driveTransitFuelage[index] = hourData.get(MODE_DRIVE_TRANSIT) == null ? 0 : hourData.get(MODE_DRIVE_TRANSIT);
            walkTransitFuelage[index] = hourData.get(MODE_WALK_TRANSIT) == null ? 0 : hourData.get(MODE_WALK_TRANSIT);
            rideFuelage[index] = hourData.get(MODE_RIDE_HAILING) == null ? 0 : hourData.get(MODE_RIDE_HAILING);
            busFuelage[index] = hourData.get(MODE_BUS) == null ? 0 : hourData.get(MODE_BUS);

            index = index + 1;
        }

        dataset[0] = carFuelage;
        dataset[1] = walkFuelage;
        dataset[2] = driveTransitFuelage;
        dataset[3] = walkTransitFuelage;
        dataset[4] = rideFuelage;
        dataset[5] = busFuelage;

        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    //
    private void processDeadHeading(PathTraversalEvent event){

        int hour = getEventHour(event.getTime());
        String mode = event.getAttributes().get("mode");

        String vehicle_id = event.getAttributes().get("vehicle_id");
        String num_passengers = event.getAttributes().get("num_passengers");
        if(vehicle_id.contains("ride")) {

            Map<Integer, Map<Integer, Integer>> deadHeadings = null;
            if(mode.equalsIgnoreCase("car")) {
                deadHeadings = carDeadHeadings;
            }else{
                deadHeadings = busDeadHeadings;
            }

            if(deadHeadings != null) {
                try {
                    Integer _num_passengers = Integer.parseInt(num_passengers);
                    if (_num_passengers >= 0 && _num_passengers <= 4) {
                        Map<Integer, Integer> hourData = deadHeadings.get(hour);
                        if (hourData == null) {
                            hourData = new HashMap<>();
                            hourData.put(_num_passengers, 1);
                            deadHeadings.put(hour, hourData);
                        } else {
                            Integer occurrence = hourData.get(hour);
                            if (occurrence == null) {
                                occurrence = 1;
                            } else {
                                occurrence = occurrence + 1;
                            }
                            hourData.put(_num_passengers, occurrence);
                            deadHeadings.put(hour, hourData);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    //
    private CategoryDataset buildDeadHeadingDataset(Map<Integer, Map<Integer, Integer>> data){

        double[][] dataset = new double[6][data.keySet().size()];


        java.util.List<Integer> keyList = new ArrayList<>();
        keyList.addAll(data.keySet());
        Collections.sort(keyList);

        double[] p0 = new double[data.keySet().size()];
        double[] p1 = new double[data.keySet().size()];
        double[] p2 = new double[data.keySet().size()];
        double[] p3 = new double[data.keySet().size()];
        double[] p4 = new double[data.keySet().size()];


        int index = 0;
        for(int hour : keyList){

            Map<Integer, Integer> hourData = data.get(hour);

            p0[index] = hourData.get(0) == null ? 0 : hourData.get(0);
            p1[index] = hourData.get(1) == null ? 0 : hourData.get(1);
            p2[index] = hourData.get(2) == null ? 0 : hourData.get(2);
            p3[index] = hourData.get(3) == null ? 0 : hourData.get(3);
            p4[index] = hourData.get(4) == null ? 0 : hourData.get(4);

            index = index + 1;
        }

        dataset[0] = p0;
        dataset[1] = p1;
        dataset[2] = p2;
        dataset[3] = p3;
        dataset[4] = p4;


        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }


    private void createDeadHeadingGraph(CategoryDataset dataset, int iterationNumber, String mode){

        String plotTitle = "Number of Passengers per Trip";
        String xaxis = "Hour";
        String yaxis = "# trips";
        int width = 800;
        int height = 600;
        boolean show = true;
        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;

        String fileName = "";
        if(mode.equalsIgnoreCase("car")){
            fileName = "tnc_passenger_per_trip.png";
        }else if(mode.equalsIgnoreCase("bus")){
            fileName = "tnc_passenger_per_trip_bus.png";
        }

        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, fileName);

        final JFreeChart chart = ChartFactory.createStackedBarChart(
                plotTitle , xaxis, yaxis,
                dataset, orientation, show, toolTips, urls);

        chart.setBackgroundPaint(new Color(255, 255, 255));
        CategoryPlot plot = chart.getCategoryPlot();

        LegendItemCollection legendItems = new LegendItemCollection();
        legendItems.add(new LegendItem("p0", MODE_CAR_COLOR));
        legendItems.add(new LegendItem("p1", MODE_WALK_COLOR));
        legendItems.add(new LegendItem("p2", MODE_DRIVE_TRANSIT_COLOR));
        legendItems.add(new LegendItem("p3", MODE_WALK_TRANSIT_COLOR));
        legendItems.add(new LegendItem("p4", MODE_RIDE_HAILING_COLOR));
        plot.setFixedLegendItems(legendItems);

        plot.getRenderer().setSeriesPaint(0, MODE_CAR_COLOR);
        plot.getRenderer().setSeriesPaint(1, MODE_WALK_COLOR);
        plot.getRenderer().setSeriesPaint(2, MODE_DRIVE_TRANSIT_COLOR);
        plot.getRenderer().setSeriesPaint(3, MODE_WALK_TRANSIT_COLOR);
        plot.getRenderer().setSeriesPaint(4, MODE_RIDE_HAILING_COLOR);


        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }


}

