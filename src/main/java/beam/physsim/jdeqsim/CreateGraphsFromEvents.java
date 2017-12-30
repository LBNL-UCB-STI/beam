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
import java.util.List;


/**
 * @Authors asif and rwaraich.
 */
public class CreateGraphsFromEvents implements BasicEventHandler {

    public static final List<Color> colors = new ArrayList<>();

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

    private Set<String> modesChosen = new TreeSet<>();
    private Set<String> modesFuel = new TreeSet<>();

    int carModeOccurrence = 0;

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

    // No Arg Constructor
    public CreateGraphsFromEvents() {  }

    // Constructor
    public CreateGraphsFromEvents(EventsManager eventsManager, OutputDirectoryHierarchy controlerIO, Scenario scenario, GeoUtils geoUtils, ActorRef registry, ActorRef router, Integer writePhysSimEventsInterval) {
        eventsManager.addHandler(this);
        this.controlerIO = controlerIO;
        this.registry = registry;
        this.router = router;
        agentSimScenario = scenario;

        this.writePhysSimEventsInterval = writePhysSimEventsInterval;
    }

    @Override
    public void reset(int iteration) {


    }

    @Override
    public void handleEvent(Event event) {

        if (event instanceof ModeChoiceEvent) {

            processModeChoice(event);
        }else if(event instanceof PathTraversalEvent){

            processFuelUsage((PathTraversalEvent)event);

            processDeadHeading((PathTraversalEvent)event);
        }
    }

    ///
    public void createGraphs(IterationEndsEvent event){

        //
        System.out.println("-- Building dataset for mode choice events --");
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDataset();
        System.out.println("-- Going to plot mode choice events --");
        createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        //
        System.out.println("-- Building dataset for mode fuel usage events --");
        CategoryDataset modesFuelageDataset = buildModesFuelageDataset();
        System.out.println("-- Going to plot mode fuel usage events --");
        createModesFuelageGraph(modesFuelageDataset, event.getIteration());

        //
        System.out.println("-- Building dataset for car mode --");
        System.out.println("car pathtraversal counts" + carModeOccurrence);
        System.out.println(Arrays.deepToString(carDeadHeadings.values().toArray()));
        CategoryDataset carDeadHeadingDataset = buildDeadHeadingDataset(carDeadHeadings);
        System.out.println("-- Going to plot the dataset for car mode --");
        createDeadHeadingGraph(carDeadHeadingDataset, event.getIteration(), CAR);

        //
        System.out.println("-- Building dataset for bus mode --");
        CategoryDataset busDeadHeadingDataset = buildDeadHeadingDataset(busDeadHeadings);
        System.out.println("-- Going to plot the dataset for bus mode --");
        createDeadHeadingGraph(busDeadHeadingDataset, event.getIteration(), BUS);
    }

    ////
    // Mode Choice Event graph
    private void processModeChoice(Event event){

        int hour = getEventHour(event.getTime());
        String mode = event.getAttributes().get("mode");
        modesChosen.add(mode);

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

    private CategoryDataset buildModesFrequencyDataset(){

        java.util.List<Integer> hoursList = new ArrayList<>();
        hoursList.addAll(hourModeFrequency.keySet());
        Collections.sort(hoursList);

        java.util.List<String> modesChosenList = new ArrayList<>();
        modesChosenList.addAll(modesChosen);
        Collections.sort(modesChosenList);
        System.out.println(Arrays.toString(modesChosenList.toArray()));

        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[modesChosen.size()][maxHour + 1];

        for(int i=0; i < modesChosenList.size(); i++){
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            String modeChosen = modesChosenList.get(i);
            int index = 0;
            for(int hour = 0; hour <= maxHour; hour++){
                Map<String, Integer> hourData = hourModeFrequency.get(hour);
                if(hourData != null) {
                    modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
                }else{
                    modeOccurrencePerHour[index] = 0;
                }
                index = index + 1;
            }
            System.out.println(Arrays.toString(modeOccurrencePerHour));
            dataset[i] = modeOccurrencePerHour;
        }

        System.out.println(Arrays.deepToString(dataset));
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
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

        System.out.println("rows " + dataset.getRowCount());
        System.out.println("cols " + dataset.getColumnCount());

        LegendItemCollection legendItems = new LegendItemCollection();

        java.util.List<String> modesChosenList = new ArrayList<>();
        modesChosenList.addAll(modesChosen);
        Collections.sort(modesChosenList);

        System.out.println(Arrays.toString(modesChosenList.toArray()));

        for (int i = 0; i < dataset.getRowCount(); i++) {

            legendItems.add(new LegendItem(modesChosenList.get(i), colors.get(i)));

            plot.getRenderer().setSeriesPaint(i, colors.get(i));

        }
        plot.setFixedLegendItems(legendItems);


        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }

    ////
    // fuel usage graph
    private void processFuelUsage(PathTraversalEvent event){

        int hour = getEventHour(event.getTime());
        String mode = event.getAttributes().get("mode");

        String fuel = event.getAttributes().get("fuel");

        modesFuel.add(mode);

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

    private CategoryDataset buildModesFuelageDataset(){

        java.util.List<Integer> keyList = new ArrayList<>();
        keyList.addAll(hourModeFuelage.keySet());
        Collections.sort(keyList);

        java.util.List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);

        System.out.println(Arrays.toString(modesFuelList.toArray()));

        int maxHour = keyList.get(keyList.size() - 1);
        double[][] dataset = new double[modesFuel.size()][maxHour + 1];

        for(int i=0; i < modesFuelList.size(); i++){
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            String modeChosen = modesFuelList.get(i);
            int index = 0;
            for(int hour = 0; hour <= maxHour; hour++){
                Map<String, Double> hourData = hourModeFuelage.get(hour);
                if(hourData != null) {
                    modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
                }else{
                    modeOccurrencePerHour[index] = 0;
                }
                index = index + 1;
            }
            System.out.println(Arrays.toString(modeOccurrencePerHour));
            dataset[i] = modeOccurrencePerHour;
        }

        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

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

        System.out.println("rows " + dataset.getRowCount());
        System.out.println("cols " + dataset.getColumnCount());

        LegendItemCollection legendItems = new LegendItemCollection();

        java.util.List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);

        System.out.println(Arrays.toString(modesFuelList.toArray()));

        for (int i = 0; i<dataset.getRowCount(); i++) {

            legendItems.add(new LegendItem(modesFuelList.get(i), colors.get(i)));
            plot.getRenderer().setSeriesPaint(i, colors.get(i));
        }
        plot.setFixedLegendItems(legendItems);

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width, height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }

    private void processDeadHeading(PathTraversalEvent event){

        int hour = getEventHour(event.getTime());
        String mode = event.getAttributes().get("mode");

        String vehicle_id = event.getAttributes().get("vehicle_id");
        String num_passengers = event.getAttributes().get("num_passengers");

        String vehicle_id_partial = "";
        Map<Integer, Map<Integer, Integer>> deadHeadings = null;
        if(mode.equalsIgnoreCase("car")) {
            deadHeadings = carDeadHeadings;
            vehicle_id_partial = "ride";

        }else{
            deadHeadings = busDeadHeadings;
            vehicle_id_partial = "bus";
        }

        if(deadHeadings != null && vehicle_id.contains(vehicle_id_partial)) {
            try {
                Integer _num_passengers = Integer.parseInt(num_passengers);
                if (_num_passengers >= 0 && _num_passengers <= 4) {

                    if(mode.equalsIgnoreCase("car")) carModeOccurrence++;
                    Map<Integer, Integer> hourData = deadHeadings.get(hour);
                    if (hourData == null) {
                        hourData = new HashMap<>();
                        hourData.put(_num_passengers, 1);
                    } else {
                        Integer occurrence = hourData.get(_num_passengers);
                        if (occurrence == null) {
                            occurrence = 1;
                        } else {
                            occurrence = occurrence + 1;
                        }
                        hourData.put(_num_passengers, occurrence);
                    }
                    deadHeadings.put(hour, hourData);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    //
    private CategoryDataset buildDeadHeadingDataset(Map<Integer, Map<Integer, Integer>> data){

        java.util.List<Integer> keyList = new ArrayList<>();
        keyList.addAll(data.keySet());
        Collections.sort(keyList);

        int maxHour = keyList.get(keyList.size() - 1);


        System.out.println("KeyList Size : " + keyList.size());
        System.out.println("KeyList max hour: " + maxHour);
        System.out.println("KeyList: " + Arrays.toString(keyList.toArray()));

        double[][] dataset = new double[5][maxHour + 1];

        double[] p0 = new double[maxHour + 1];
        double[] p1 = new double[maxHour + 1];
        double[] p2 = new double[maxHour + 1];
        double[] p3 = new double[maxHour + 1];
        double[] p4 = new double[maxHour + 1];

        int index = 0;
        for(int hour = 0; hour <= maxHour; hour++){

            Map<Integer, Integer> hourData = data.get(hour);

            if(hourData != null) {
                p0[index] = hourData.get(0) == null ? 0 : hourData.get(0);
                p1[index] = hourData.get(1) == null ? 0 : hourData.get(1);
                p2[index] = hourData.get(2) == null ? 0 : hourData.get(2);
                p3[index] = hourData.get(3) == null ? 0 : hourData.get(3);
                p4[index] = hourData.get(4) == null ? 0 : hourData.get(4);
            }else{
                p0[index] = 0;
                p1[index] = 0;
                p2[index] = 0;
                p3[index] = 0;
                p4[index] = 0;
            }

            index = index + 1;
        }

        dataset[0] = p0;
        dataset[1] = p1;
        dataset[2] = p2;
        dataset[3] = p3;
        dataset[4] = p4;

        System.out.println("Dataset to plot: " + Arrays.deepToString(dataset));


        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    private void createDeadHeadingGraph(CategoryDataset dataset, int iterationNumber, String mode){

        String plotTitle = "Number of Passengers per Trip [TNC]";
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
            fileName = "bus_passenger_per_trip.png";
            plotTitle = "Number of Passengers per Trip [BUS]";
        }

        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, fileName);

        final JFreeChart chart = ChartFactory.createStackedBarChart(
                plotTitle , xaxis, yaxis,
                dataset, orientation, show, toolTips, urls);

        chart.setBackgroundPaint(new Color(255, 255, 255));
        CategoryPlot plot = chart.getCategoryPlot();

        LegendItemCollection legendItems = new LegendItemCollection();
        legendItems.add(new LegendItem("p0", colors.get(0)));
        legendItems.add(new LegendItem("p1", colors.get(1)));
        legendItems.add(new LegendItem("p2", colors.get(2)));
        legendItems.add(new LegendItem("p3", colors.get(3)));
        legendItems.add(new LegendItem("p4", colors.get(4)));
        plot.setFixedLegendItems(legendItems);

        plot.getRenderer().setSeriesPaint(0, colors.get(0));
        plot.getRenderer().setSeriesPaint(1, colors.get(1));
        plot.getRenderer().setSeriesPaint(2, colors.get(2));
        plot.getRenderer().setSeriesPaint(3, colors.get(3));
        plot.getRenderer().setSeriesPaint(4, colors.get(4));


        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }
}