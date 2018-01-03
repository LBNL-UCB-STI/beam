package beam.physsim.jdeqsim;

import akka.actor.ActorRef;
import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.sim.common.GeoUtils;
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
public class CreateGraphsFromAgentSimEvents implements BasicEventHandler {

    public static final List<Color> colors = new ArrayList<>();

    public static final String CAR = "car";
    public static final String BUS = "bus";
    public static final String DUMMY_ACTIVITY = "DummyActivity";
    private ActorRef router;
    private OutputDirectoryHierarchy controlerIO;
    private Logger log = LoggerFactory.getLogger(CreateGraphsFromAgentSimEvents.class);
    private Scenario jdeqSimScenario;
    private PopulationFactory populationFactory;
    private Scenario agentSimScenario;
    private ActorRef registry;

    private ActorRef eventHandlerActorREF;
    private ActorRef jdeqsimActorREF;
    private EventsManager eventsManager;
    private int numberOfLinksRemovedFromRouteAsNonCarModeLinks;

    private Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private Map<Integer, Map<String, Double>> hourModeFuelage = new HashMap<>();


    private Map<String, Map<Integer, Map<Integer, Integer>>> deadHeadingsMap = new HashMap<>();
    Map<Integer, Map<Integer, Double>> deadHeadingsTnc0Map = new HashMap<>();

    private Map<String, Map<Id<Person>, PersonDepartureEvent>> personLastDepartureEvents = new HashMap<>();

    private Map<String, Map<Integer, List<Double>>> hourlyPersonTravelTimes = new HashMap<>();

    /*private Map<Integer, Map<Integer, Integer>> carDeadHeadings = new HashMap<>();
    private Map<Integer, Map<Integer, Integer>> busDeadHeadings = new HashMap<>();*/

    private Set<String> modesChosen = new TreeSet<>();
    private Set<String> modesFuel = new TreeSet<>();

    int carModeOccurrence = 0;
    int maxPassengersSeenOnGenericCase = 0;

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
    public CreateGraphsFromAgentSimEvents() {  }

    // Constructor
    public CreateGraphsFromAgentSimEvents(EventsManager eventsManager, OutputDirectoryHierarchy controlerIO, Scenario scenario, GeoUtils geoUtils, ActorRef registry, ActorRef router) {
        eventsManager.addHandler(this);
        this.controlerIO = controlerIO;
        this.registry = registry;
        this.router = router;
        agentSimScenario = scenario;

        // initialize transit vehicles for fuel energy consumption calculations
        PathTraversalSpatialTemporalTableGenerator.setVehicles(scenario.getTransitVehicles());
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
        }else if(event instanceof PersonDepartureEvent){

            PersonDepartureEvent personDepartureEvent = (PersonDepartureEvent)event;

            String mode = ((PersonDepartureEvent) event).getLegMode();
            Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
            if(departureEvents == null) {
                departureEvents = new HashMap<>();
            }
            departureEvents.put(((PersonDepartureEvent) event).getPersonId(), personDepartureEvent);
            personLastDepartureEvents.put(mode, departureEvents);
        }else if(event instanceof PersonArrivalEvent){

            String mode = ((PersonArrivalEvent) event).getLegMode();

            Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
            if(departureEvents != null){

                PersonArrivalEvent personArrivalEvent = (PersonArrivalEvent)event;

                Id<Person> personId = personArrivalEvent.getPersonId();
                PersonDepartureEvent personDepartureEvent = departureEvents.get(personId);

                if(personDepartureEvent != null) {
                    int basketHour = getEventHour(personDepartureEvent.getTime());

                    Double travelTime = personArrivalEvent.getTime() - personDepartureEvent.getTime();

                    Map<Integer, List<Double>> hourlyPersonTravelTimesPerMode = hourlyPersonTravelTimes.get(mode);
                    if(hourlyPersonTravelTimesPerMode == null) {
                        hourlyPersonTravelTimesPerMode = new HashMap<>();
                        List<Double> travelTimes = new ArrayList<>();
                        travelTimes.add(travelTime);
                        hourlyPersonTravelTimesPerMode.put(basketHour, travelTimes);
                    }else{
                        List<Double> travelTimes = hourlyPersonTravelTimesPerMode.get(basketHour);
                        if (travelTimes == null) {
                            travelTimes = new ArrayList<>();
                            travelTimes.add(travelTime);
                        }else{

                            travelTimes.add(travelTime);
                        }
                        hourlyPersonTravelTimesPerMode.put(basketHour, travelTimes);
                    }

                    hourlyPersonTravelTimes.put(mode, hourlyPersonTravelTimesPerMode);

                    personLastDepartureEvents.remove(personId);
                }
            }
        }
    }



    ///
    public void createGraphs(IterationEndsEvent event){

        //
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDataset();
        createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        //
        CategoryDataset modesFuelageDataset = buildModesFuelageDataset();
        createModesFuelageGraph(modesFuelageDataset, event.getIteration());


        CategoryDataset tnc0DeadHeadingDataset = buildDeadHeadingDatasetTnc0(deadHeadingsTnc0Map, "tnc_deadheading_distance");
        createDeadHeadingGraphTnc0(tnc0DeadHeadingDataset, event.getIteration(), "tnc_deadheading_distance");

        //
        List<String> graphNamesList = new ArrayList<>(deadHeadingsMap.keySet());
        Collections.sort(graphNamesList);

        for(String graphName : graphNamesList){

            CategoryDataset tncDeadHeadingDataset = buildDeadHeadingDataset(deadHeadingsMap.get(graphName), graphName);
            createDeadHeadingGraph(tncDeadHeadingDataset, event.getIteration(), graphName);
        }

        for(String mode : hourlyPersonTravelTimes.keySet()){

            CategoryDataset averageDataset = buildAverageTimesDataset(hourlyPersonTravelTimes.get(mode), mode);
            createAverageTimesGraph(averageDataset, event.getIteration(), mode);
        }
        //
    }

    private CategoryDataset buildAverageTimesDataset(Map<Integer, List<Double>> times, String mode){

        java.util.List<Integer> hoursList = new ArrayList<>();
        hoursList.addAll(times.keySet());
        Collections.sort(hoursList);

        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[1][maxHour + 1];

        double[] travelTimes = new double[maxHour + 1];
        for(int i=0; i<maxHour; i++){

            List<Double> hourData = times.get(i);
            Double average = 0d;
            if(hourData != null) {
                average = hourData.stream().mapToDouble(val -> val).average().getAsDouble();
            }
            travelTimes[i] = average;
        }

        dataset[0] = travelTimes;

        return DatasetUtilities.createCategoryDataset(mode, "", dataset);
    }

    private void createAverageTimesGraph(CategoryDataset dataset, int iterationNumber, String mode){

        String fileName = "average_travel_times_" + mode + ".png";
        String plotTitle = "Average Travel Time [" + mode + "]";
        String xaxis = "Hour";
        String yaxis = "Average Travel Time [min]";
        int width = 800;
        int height = 600;
        boolean showLegend = false;
        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, fileName);

        final JFreeChart chart = ChartFactory.createStackedBarChart(
                plotTitle , xaxis, yaxis,
                dataset, orientation, showLegend, toolTips, urls);

        chart.setBackgroundPaint(new Color(255, 255, 255));
        CategoryPlot plot = chart.getCategoryPlot();



        for (int i = 0; i < dataset.getRowCount(); i++) {


            plot.getRenderer().setSeriesPaint(i, colors.get(i));

        }



        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
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

            Integer frequency = hourData.get(mode);

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
            dataset[i] = modeOccurrencePerHour;
        }

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

        LegendItemCollection legendItems = new LegendItemCollection();

        java.util.List<String> modesChosenList = new ArrayList<>();
        modesChosenList.addAll(modesChosen);
        Collections.sort(modesChosenList);


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



        String vehicleType = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
        String mode = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicleId = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        double lengthInMeters = Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LENGTH));
        String fuelString = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_FUEL);

        modesFuel.add(mode);

        try{

            Double fuel = PathTraversalSpatialTemporalTableGenerator.getFuelConsumptionInMJ(vehicleId,mode,fuelString,lengthInMeters,vehicleType);;

            Map<String, Double> hourData = hourModeFuelage.get(hour);
            if (hourData == null) {

                hourData = new HashMap<>();
                hourData.put(mode, fuel);
                hourModeFuelage.put(hour, hourData);
            } else {

                Double fuelage = hourData.get(mode);

                if (fuelage == null) {
                    fuelage = fuel;
                } else {
                    fuelage = fuelage + fuel;
                }

                hourData.put(mode, fuelage);
                hourModeFuelage.put(hour, hourData);
            }
        }catch (Exception e){

            e.printStackTrace();
        }
    }

    private CategoryDataset buildModesFuelageDataset(){

        java.util.List<Integer> hours = new ArrayList<>();
        hours.addAll(hourModeFuelage.keySet());
        Collections.sort(hours);

        java.util.List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);


        int maxHour = hours.get(hours.size() - 1);
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
            dataset[i] = modeOccurrencePerHour;
        }

        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    private void createModesFuelageGraph(CategoryDataset dataset, int iterationNumber){

        String plotTitle = "Energy Use by Mode";
        String xaxis = "Hour";
        String yaxis = "Energy Use [MJ]";
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

        java.util.List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);

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

        String graphName = mode;

        Map<Integer, Map<Integer, Integer>> deadHeadings = null;
        if(mode.equalsIgnoreCase("car") && vehicle_id.contains("ride")) {
            graphName = "tnc";
        }

        Integer _num_passengers = null;
        try {
            _num_passengers = Integer.parseInt(num_passengers);
        }catch (NumberFormatException nfe) {
            nfe.printStackTrace();
        }

        boolean validCase = isValidCase(graphName, _num_passengers);

        if (validCase) {

            deadHeadings = deadHeadingsMap.get(graphName);

            Map<Integer, Integer> hourData = null;
            if(deadHeadings != null)
                hourData = deadHeadings.get(hour);

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

            if(deadHeadings == null){
                deadHeadings = new HashMap<>();
            }
            deadHeadings.put(hour, hourData);

            deadHeadingsMap.put(graphName, deadHeadings);
        }

        if(graphName.equalsIgnoreCase("tnc") && _num_passengers == 0){



            Double length = Double.parseDouble(event.getAttributes().get("length"));


            Map<Integer, Double> hourData = deadHeadingsTnc0Map.get(hour);

            if (hourData == null) {
                hourData = new HashMap<>();
                hourData.put(_num_passengers, length);
            } else {
                Double distance = hourData.get(_num_passengers);
                if (distance == null) {
                    distance = length;
                } else {
                    distance = distance + length;
                }
                hourData.put(_num_passengers, distance);
            }

            deadHeadingsTnc0Map.put(hour, hourData);
        }
    }

    //
    private CategoryDataset buildDeadHeadingDataset(Map<Integer, Map<Integer, Integer>> data, String graphName){

        java.util.List<Integer> hours = new ArrayList<>();
        hours.addAll(data.keySet());
        Collections.sort(hours);

        int maxHour = hours.get(hours.size() - 1);

        Integer maxPassengers = null;
        if(graphName.equalsIgnoreCase("car")){
            maxPassengers = 4;
        }else if(graphName.equalsIgnoreCase("tnc")) {
            maxPassengers = 6;
        }else{
            maxPassengers = maxPassengersSeenOnGenericCase;
        }

        double dataset[][] = null;

        if(graphName.equalsIgnoreCase("tnc") || graphName.equalsIgnoreCase("car")) {

            dataset = new double[maxPassengers + 1][maxHour + 1];

            for (int i = 0; i <= maxPassengers; i++) {
                double[] modeOccurrencePerHour = new double[maxHour + 1];
                //String passengerCount = "p" + i;
                int index = 0;
                for (int hour = 0; hour <= maxHour; hour++) {
                    Map<Integer, Integer> hourData = data.get(hour);
                    if (hourData != null) {
                        modeOccurrencePerHour[index] = hourData.get(i) == null ? 0 : hourData.get(i);
                    } else {
                        modeOccurrencePerHour[index] = 0;
                    }
                    index = index + 1;
                }
                dataset[i] = modeOccurrencePerHour;
            }
        }else{

            // This loop gives the loop over all the different passenger groups, which is 1 in other cases.
            // In this case we have to group 0, 1 to 5, 6 to 10

            int bucketSize = getBucketSize();

            dataset = new double[5][maxHour + 1];


            // We need only 5 buckets
            // The modeOccurrentPerHour array index will not go beyond 5 as all the passengers will be
            // accomodated within the 4 buckets because the index will not be incremented until all
            // passengers falling in one bucket are added into that index of modeOccurrencePerHour
            double[] modeOccurrencePerHour = new double[maxHour+1];
            int bucket = 0;

            for (int i = 0; i <= maxPassengers; i++) {

                //String passengerCount = "p" + i;
                int index = 0;
                for (int hour = 0; hour <= maxHour; hour++) {
                    Map<Integer, Integer> hourData = data.get(hour);
                    if (hourData != null) {
                        modeOccurrencePerHour[index] += hourData.get(i) == null ? 0 : hourData.get(i);
                    } else {
                        modeOccurrencePerHour[index] += 0;
                    }
                    index = index + 1;
                }

                if(i == 0 || (i % bucketSize == 0) || i == maxPassengers) {

                    dataset[bucket] = modeOccurrencePerHour;

                    modeOccurrencePerHour = new double[maxHour + 1];
                    bucket = bucket + 1;
                }
            }
        }


        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    private CategoryDataset buildDeadHeadingDatasetTnc0(Map<Integer, Map<Integer, Double>> data, String graphName){

        java.util.List<Integer> hours = new ArrayList<>();
        hours.addAll(data.keySet());
        Collections.sort(hours);

        int maxHour = hours.get(hours.size() - 1);

        Integer maxPassengers = 0;

        double dataset[][] = null;

        dataset = new double[maxPassengers + 1][maxHour + 1];

        for (int i = 0; i <= maxPassengers; i++) {
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            //String passengerCount = "p" + i;
            int index = 0;
            for (int hour = 0; hour <= maxHour; hour++) {
                Map<Integer, Double> hourData = data.get(hour);
                if (hourData != null) {
                    modeOccurrencePerHour[index] = hourData.get(i) == null ? 0 : hourData.get(i)/1000;
                } else {
                    modeOccurrencePerHour[index] = 0;
                }
                index = index + 1;
            }
            dataset[i] = modeOccurrencePerHour;
        }

        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    private int getBucketSize(){
        return (int)Math.ceil(maxPassengersSeenOnGenericCase/4.0);
    }

    private void createDeadHeadingGraph(CategoryDataset dataset, int iterationNumber, String graphName){

        String fileName = getGraphFileName(graphName);
        String plotTitle = getTitle(graphName);
        String xaxis = "Hour";
        String yaxis = "# trips";
        int width = 800;
        int height = 600;
        boolean show = true;
        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;

        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, fileName);

        final JFreeChart chart = ChartFactory.createStackedBarChart(
                plotTitle , xaxis, yaxis,
                dataset, orientation, show, toolTips, urls);

        chart.setBackgroundPaint(new Color(255, 255, 255));
        CategoryPlot plot = chart.getCategoryPlot();

        LegendItemCollection legendItems = new LegendItemCollection();

        for (int i = 0; i<dataset.getRowCount(); i++) {

            Color color = getBarAndLegendColor(i);
            legendItems.add(new LegendItem(getLegendText(graphName, i), color));
            plot.getRenderer().setSeriesPaint(i, color);
        }
        plot.setFixedLegendItems(legendItems);

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width, height);
        } catch (IOException e) {

            e.printStackTrace();
        }

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }

    private void createDeadHeadingGraphTnc0(CategoryDataset dataset, int iterationNumber, String graphName){

        String fileName = getGraphFileName(graphName);
        String plotTitle = getTitle(graphName);
        String xaxis = "Hour";
        String yaxis = "Distance in kilometers";
        int width = 800;
        int height = 600;
        boolean show = true;
        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;

        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, fileName);

        final JFreeChart chart = ChartFactory.createStackedBarChart(
                plotTitle , xaxis, yaxis,
                dataset, orientation, show, toolTips, urls);

        chart.setBackgroundPaint(new Color(255, 255, 255));
        CategoryPlot plot = chart.getCategoryPlot();

        LegendItemCollection legendItems = new LegendItemCollection();

        for (int i = 0; i<dataset.getRowCount(); i++) {

            Color color = getBarAndLegendColor(i);
            legendItems.add(new LegendItem(getLegendText(graphName, i), color));
            plot.getRenderer().setSeriesPaint(i, color);
        }
        plot.setFixedLegendItems(legendItems);

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width, height);
        } catch (IOException e) {

            e.printStackTrace();
        }

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }


    // helper methods
    private boolean isValidCase(String graphName, int numPassengers){
        boolean validCase = false;

        if(!graphName.equalsIgnoreCase("walk")) {
            if (graphName.equalsIgnoreCase("tnc") && numPassengers >= 0 && numPassengers <= 6) {

                validCase = true;
            } else if (graphName.equalsIgnoreCase("car") && numPassengers >= 0 && numPassengers <= 4) {
                validCase = true;
            } else {
                if (maxPassengersSeenOnGenericCase < numPassengers) maxPassengersSeenOnGenericCase = numPassengers;
                validCase = true;
            }
        }

        return validCase;
    }

    private String getLegendText(String graphName, int i){

        int bucketSize = getBucketSize();
        if(graphName.equalsIgnoreCase("car") || graphName.equalsIgnoreCase("tnc")) {
            return "p" + i;
        }else{
            if(i == 0){
                return "0";
            }else{
                int start = (i - 1) * bucketSize + 1;
                int end = (i - 1) * bucketSize + bucketSize;
                return  start + "-" + end;
            }
        }
    }

    private String getGraphFileName(String graphName){
        if(graphName.equalsIgnoreCase("tnc")) {
            return "tnc_passenger_per_trip.png";
        }else if(graphName.equalsIgnoreCase("tnc_deadheading_distance")) {
            return "tnc_deadheading_distance.png";
        }else{
            return graphName + "_passenger_per_trip.png";
        }

    }

    private String getTitle(String graphName){
        if(graphName.equalsIgnoreCase("tnc")){
            return "Number of Passengers per Trip [TNC]";
        }else if(graphName.equalsIgnoreCase("tnc_deadheading_distance")){
            return "Dead Heading Distance [TNC]";
        }else if(graphName.equalsIgnoreCase("car")) {
            return "Number of Passengers per Trip [Car]";
        }else{
            return "Number of Passengers per Trip [" + graphName + "]";
        }
    }

    private Color getBarAndLegendColor(int i) {
        if(i < colors.size()){
            return colors.get(i);
        }else{
            return getRandomColor();
        }
    }

    private Color getRandomColor(){


        Random rand = new Random();

// Java 'Color' class takes 3 floats, from 0 to 1.
        float r = rand.nextFloat();
        float g = rand.nextFloat();
        float b = rand.nextFloat();

        Color randomColor = new Color(r, g, b);
        return randomColor;
    }

}