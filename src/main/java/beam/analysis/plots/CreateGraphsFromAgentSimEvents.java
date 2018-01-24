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

    private static final int SECONDS_IN_MINUTE = 60;
    private static final int SECONDS_IN_HOUR = 3600;
    private static final int METERS_IN_KM = 1000;

    public static final List<Color> colors = new ArrayList<>();

    public static final String CAR = "car";
    private OutputDirectoryHierarchy controlerIO;

    private Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private Map<Integer, Map<String, Double>> hourModeFuelage = new HashMap<>();


    private Map<String, Map<Integer, Map<Integer, Integer>>> deadHeadingsMap = new HashMap<>();
    private Map<Integer, Map<Integer, Double>> deadHeadingsTnc0Map = new HashMap<>();

    private Map<String, Map<Id<Person>, PersonDepartureEvent>> personLastDepartureEvents = new HashMap<>();

    private Map<String, Map<Integer, List<Double>>> hourlyPersonTravelTimes = new HashMap<>();

    private Set<String> modesChosen = new TreeSet<>();
    private Set<String> modesFuel = new TreeSet<>();

    int carModeOccurrence = 0;
    int maxPassengersSeenOnGenericCase = 0;

    public static final Integer TNC_MAX_PASSENGERS = 6;
    public static final Integer CAR_MAX_PASSENGERS = 4;

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

        hourModeFrequency.clear();
        hourModeFuelage.clear();

        deadHeadingsMap.clear();
        deadHeadingsTnc0Map.clear();

        personLastDepartureEvents.clear();

        hourlyPersonTravelTimes.clear();

        modesChosen.clear();
        modesFuel.clear();

        carModeOccurrence = 0;
        maxPassengersSeenOnGenericCase = 0;
    }

    @Override
    public void handleEvent(Event event) {

        if (event instanceof ModeChoiceEvent || event.getEventType().equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)) {

            processModeChoice(event);
        } else if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {

            processFuelUsage(event);

            processDeadHeading(event);
        } else if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)) {

            PersonDepartureEvent personDepartureEvent = (PersonDepartureEvent) event;

            String mode = ((PersonDepartureEvent) event).getLegMode();
            Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
            if (departureEvents == null) {
                departureEvents = new HashMap<>();
            }
            departureEvents.put(((PersonDepartureEvent) event).getPersonId(), personDepartureEvent);
            personLastDepartureEvents.put(mode, departureEvents);
        } else if (event instanceof PersonArrivalEvent || event.getEventType().equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE)) {

            String mode = ((PersonArrivalEvent) event).getLegMode();

            Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
            if (departureEvents != null) {

                PersonArrivalEvent personArrivalEvent = (PersonArrivalEvent) event;

                Id<Person> personId = personArrivalEvent.getPersonId();
                PersonDepartureEvent personDepartureEvent = departureEvents.get(personId);

                if (personDepartureEvent != null) {
                    int basketHour = getEventHour(personDepartureEvent.getTime());

                    Double travelTime = (personArrivalEvent.getTime() - personDepartureEvent.getTime()) / SECONDS_IN_MINUTE;

                    Map<Integer, List<Double>> hourlyPersonTravelTimesPerMode = hourlyPersonTravelTimes.get(mode);
                    if (hourlyPersonTravelTimesPerMode == null) {
                        hourlyPersonTravelTimesPerMode = new HashMap<>();
                        List<Double> travelTimes = new ArrayList<>();
                        travelTimes.add(travelTime);
                        hourlyPersonTravelTimesPerMode.put(basketHour, travelTimes);
                    } else {
                        List<Double> travelTimes = hourlyPersonTravelTimesPerMode.get(basketHour);
                        if (travelTimes == null) {
                            travelTimes = new ArrayList<>();
                            travelTimes.add(travelTime);
                        } else {

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
    public void createGraphs(IterationEndsEvent event) throws IOException {

        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        CategoryDataset modesFuelageDataset = buildModesFuelageGraphDataset();
        createModesFuelageGraph(modesFuelageDataset, event.getIteration());

        CategoryDataset tnc0DeadHeadingDataset = buildDeadHeadingDatasetTnc0ForGraph();
        createDeadHeadingGraphTnc0(tnc0DeadHeadingDataset, event.getIteration(), "tnc_deadheading_distance");

        List<String> graphNamesList = getSortedDeadHeadingMapList();
        for (String graphName : graphNamesList) {
            CategoryDataset tncDeadHeadingDataset = buildDeadHeadingDataForGraph(graphName);
            createDeadHeadingGraph(tncDeadHeadingDataset, event.getIteration(), graphName);
        }
        for (String mode : hourlyPersonTravelTimes.keySet()) {
            CategoryDataset averageDataset = buildAverageTimesDatasetGraph( mode);
            createAverageTimesGraph(averageDataset, event.getIteration(), mode);
        }
    }
    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {

        String graphTitle = "Mode Choice Histogram";
        String xAxisTitle = "Hour";
        String yAxisTitle = "# mode chosen";
        String fileName = "mode_choice.png";

        boolean legend = true;
        final JFreeChart chart = createStackedBarChart(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>();
        modesChosenList.addAll(modesChosen);
        Collections.sort(modesChosenList);
        processAndPlotLegendItems(plot,modesChosenList,dataset.getRowCount());
        saveJFreeChartAsPNG(chart,iterationNumber,fileName);

    }
    private void createModesFuelageGraph(CategoryDataset dataset, int iterationNumber) throws IOException {

        String graphTitle = "Energy Use by Mode";
        String xAxisTitle = "Hour";
        String yAxisTitle = "Energy Use [MJ]";
        String fileName = "energy_use.png";

        boolean legend = true;
        final JFreeChart chart = createStackedBarChart(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);
        processAndPlotLegendItems(plot,modesFuelList,dataset.getRowCount());
        saveJFreeChartAsPNG(chart,iterationNumber,fileName);
    }
    private void createDeadHeadingGraphTnc0(CategoryDataset dataset, int iterationNumber, String graphName) throws IOException {

        String fileName = getGraphFileName(graphName);
        String graphTitle = getTitle(graphName);
        String xAxisTitle = "Hour";
        String yAxisTitle = "Distance in kilometers";
        boolean legend = true;
        final JFreeChart chart = createStackedBarChart(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        processAndPlotLegendItems(plot,graphName,dataset.getRowCount());
        saveJFreeChartAsPNG(chart,iterationNumber,fileName);

    }
    private void createDeadHeadingGraph(CategoryDataset dataset, int iterationNumber, String graphName) throws IOException{


        String fileName = getGraphFileName(graphName);
        String graphTitle = getTitle(graphName);
        String xAxisTitle = "Hour";
        String yAxisTitle = "# trips";
        boolean legend = true;
        final JFreeChart chart = createStackedBarChart(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        processAndPlotLegendItems(plot,graphName,dataset.getRowCount());
        saveJFreeChartAsPNG(chart,iterationNumber,fileName);

    }
    private void createAverageTimesGraph(CategoryDataset dataset, int iterationNumber, String mode) throws IOException {

        String fileName = "average_travel_times_" + mode + ".png";
        String graphTitle = "Average Travel Time [" + mode + "]";
        String xAxisTitle = "Hour";
        String yAxisTitle = "Average Travel Time [min]";
        boolean legend = false;

        final JFreeChart chart = createStackedBarChart(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        processAndPlotLegendItems(plot,dataset.getRowCount());
        saveJFreeChartAsPNG(chart,iterationNumber,fileName);
    }
    private JFreeChart createStackedBarChart(CategoryDataset dataset,String graphTitle,String xAxisTitle,String yAxisTitle,String fileName,boolean legend){

        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        final JFreeChart chart = ChartFactory.createStackedBarChart(
                graphTitle, xAxisTitle, yAxisTitle,
                dataset, orientation, legend, toolTips, urls);
        chart.setBackgroundPaint(new Color(255, 255, 255));

        return chart;
    }
    private void processAndPlotLegendItems(CategoryPlot plot,List<String> legendItemName,int dataSetRowCount){
        LegendItemCollection legendItems = new LegendItemCollection();
        for (int i = 0; i < dataSetRowCount; i++) {
            legendItems.add(new LegendItem(legendItemName.get(i), colors.get(i)));
            plot.getRenderer().setSeriesPaint(i, colors.get(i));
        }
        plot.setFixedLegendItems(legendItems);
    }
    private void processAndPlotLegendItems(CategoryPlot plot,String graphName,int dataSetRowCount){
        LegendItemCollection legendItems = new LegendItemCollection();
        for (int i = 0; i < dataSetRowCount; i++) {
            Color color = getBarAndLegendColor(i);
            legendItems.add(new LegendItem(getLegendText(graphName, i), color));
            plot.getRenderer().setSeriesPaint(i, colors.get(i));
        }
        plot.setFixedLegendItems(legendItems);
    }
    private void processAndPlotLegendItems(CategoryPlot plot,int dataSetRowCount){
        LegendItemCollection legendItems = new LegendItemCollection();
        for (int i = 0; i < dataSetRowCount; i++) {
            plot.getRenderer().setSeriesPaint(i, colors.get(i));
        }
        plot.setFixedLegendItems(legendItems);
    }
    private void saveJFreeChartAsPNG(final JFreeChart chart,int iterationNumber,String fileName) throws IOException{
        int width = 800;
        int height = 600;
        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, fileName);
        ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width, height);
    }

    public int getHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour){
        double count = 0;
        double[] modeOccurrencePerHour = getHoursDataPerOccurrenceAgainstMode(modeChosen,maxHour);
        for(int i =0 ;i < modeOccurrencePerHour.length;i++){
            count=  count+modeOccurrencePerHour[i];
        }
        return (int)count;
    }
    public int getHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour,int hour){
        double[] modeOccurrencePerHour = getHoursDataPerOccurrenceAgainstMode(modeChosen,maxHour);
        return (int)Math.ceil(modeOccurrencePerHour[hour]);
    }
    public List<Integer> getSortedHourModeFrequencyList(){
        List<Integer> hoursList = new ArrayList<>();
        hoursList.addAll(hourModeFrequency.keySet());
        Collections.sort(hoursList);
        return hoursList;
    }
    private List<String> getSortedChosenModeList(){
        List<String> modesChosenList = new ArrayList<>();
        modesChosenList.addAll(modesChosen);
        Collections.sort(modesChosenList);
        return modesChosenList;
    }
    private double[] getHoursDataPerOccurrenceAgainstMode(String modeChosen, int maxHour){
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Integer> hourData = hourModeFrequency.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }
    private double[][] buildModesFrequencyDataset() {

        List<Integer> hoursList = getSortedHourModeFrequencyList();
        List<String> modesChosenList = getSortedChosenModeList();

        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[modesChosen.size()][maxHour + 1];
        for (int i = 0; i < modesChosenList.size(); i++) {
            String modeChosen = modesChosenList.get(i);
            dataset[i] = getHoursDataPerOccurrenceAgainstMode(modeChosen,maxHour);
        }
        return dataset;
    }
    private CategoryDataset buildModesFrequencyDatasetForGraph(){
        double [][] dataset= buildModesFrequencyDataset();
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    public List<Integer> getSortedHourModeFuelageList(){
        List<Integer> hours = new ArrayList<>();
        hours.addAll(hourModeFuelage.keySet());
        Collections.sort(hours);
        return hours;
    }
    public int getFuelageHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour){
        double count = 0;
        double[] modeOccurrencePerHour = getFuelageHourDataAgainstMode(modeChosen,maxHour);
        for(int i =0 ;i < modeOccurrencePerHour.length;i++){
            count=  count+modeOccurrencePerHour[i];
        }
        return (int)Math.ceil(count);
    }
    private List<String> getSortedModeFuleList(){
        List<String> modesFuelList = new ArrayList<>();
        modesFuelList.addAll(modesFuel);
        Collections.sort(modesFuelList);
        return modesFuelList;
    }
    private double[] getFuelageHourDataAgainstMode(String modeChosen,int maxHour){
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Double> hourData = hourModeFuelage.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }
    private CategoryDataset buildModesFuelageGraphDataset() {

        List<Integer> hours = getSortedHourModeFuelageList();
        List<String> modesFuelList = getSortedModeFuleList();
        int maxHour = hours.get(hours.size() - 1);
        double[][] dataset = new double[modesFuel.size()][maxHour + 1];
        for (int i = 0; i < modesFuelList.size(); i++) {
            String modeChosen = modesFuelList.get(i);
            dataset[i] = getFuelageHourDataAgainstMode(modeChosen,maxHour);
        }
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }



    public int getDeadHeadingTnc0HourDataCount(int hourIndex){
        double[][] dataset=buildDeadHeadingDatasetTnc0();
        double [] hoursData = dataset[hourIndex];
        double count = 0;
        for (double hourData:hoursData){
            count = count + hourData;
        }
        return (int)Math.ceil(count);
    }
    public int getDeadHeadingTnc0HourDataCount(int hourIndex,int hour){
        double[][] dataset=buildDeadHeadingDatasetTnc0();
        double [] hoursData = dataset[hourIndex];
        return (int)Math.ceil(hoursData[hour]);
    }
    private List<Integer> getSortedDeadHeadingsTnc0Map(){
        List<Integer> hours = new ArrayList<>();
        hours.addAll(deadHeadingsTnc0Map.keySet());
        Collections.sort(hours);
        return hours;
    }
    private double[] getDeadHeadingDatasetTnc0ModeOccurrencePerHour(int maxHour,int outerLoopIndex){
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        //String passengerCount = "p" + i;
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<Integer, Double> hourData = deadHeadingsTnc0Map.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(outerLoopIndex) == null ? 0 : hourData.get(outerLoopIndex) / METERS_IN_KM;
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }
    private double[][] buildDeadHeadingDatasetTnc0(){
        List<Integer> hours = getSortedDeadHeadingsTnc0Map();

        int maxHour = 0;
        if (hours.size() > 0) {
            maxHour = hours.get(hours.size() - 1);
        }

        Integer maxPassengers = TNC_MAX_PASSENGERS;

        double dataset[][] = new double[maxPassengers + 1][maxHour + 1];

        for (int i = 0; i <= maxPassengers; i++) {
            dataset[i] = getDeadHeadingDatasetTnc0ModeOccurrencePerHour(maxHour,i);
        }
        return dataset;
    }
    private CategoryDataset buildDeadHeadingDatasetTnc0ForGraph() {

        double[][] dataset=buildDeadHeadingDatasetTnc0();
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }

    public int getBucketCountAgainstMode(int bucketIndex,String mode){
        double[][] dataset=buildDeadHeadingDataset(this.deadHeadingsMap.get(mode),mode);
        double[] hoursData = dataset[bucketIndex];
        double count = 0;
        for(double hourData:hoursData){
            count = count + hourData;
        }
        return (int)Math.ceil(count);
    }
    public int getPassengerPerTripCountForSpecificHour(int bucketIndex,String mode,int hour){
        double[][] dataset=buildDeadHeadingDataset(this.deadHeadingsMap.get(mode),mode);
        double[] hoursData = dataset[bucketIndex];
        return (int)Math.ceil(hoursData[hour]);
    }
    private double[] getModeOccurrencePerHourAgainstMode(Map<Integer, Map<Integer, Integer>> data,int maxHour,int outerLoopIndex){
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        //String passengerCount = "p" + i;
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<Integer, Integer> hourData = data.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(outerLoopIndex) == null ? 0 : hourData.get(outerLoopIndex);
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }
    private double[] getModeOccurrenceOfPassengerWithBucketSize(Map<Integer, Map<Integer, Integer>> data,double[] modeOccurrencePerHour,int maxHour,int outerLoopIndex){
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<Integer, Integer> hourData = data.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] += hourData.get(outerLoopIndex) == null ? 0 : hourData.get(outerLoopIndex);
            } else {
                modeOccurrencePerHour[index] += 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }
    private List<String> getSortedDeadHeadingMapList(){
        List<String> graphNamesList = new ArrayList<>(deadHeadingsMap.keySet());
        Collections.sort(graphNamesList);
        return graphNamesList;
    }
    private double[][] buildDeadHeadingDataset(Map<Integer, Map<Integer, Integer>> data, String graphName) {

        List<Integer> hours = new ArrayList<>();
        hours.addAll(data.keySet());
        Collections.sort(hours);

        int maxHour = hours.get(hours.size() - 1);

        Integer maxPassengers = null;
        if (graphName.equalsIgnoreCase("car")) {
            maxPassengers = CAR_MAX_PASSENGERS;
        } else if (graphName.equalsIgnoreCase("tnc")) {
            maxPassengers = TNC_MAX_PASSENGERS;
        } else {
            maxPassengers = maxPassengersSeenOnGenericCase;
        }

        double dataset[][] = null;

        if (graphName.equalsIgnoreCase("tnc") || graphName.equalsIgnoreCase("car")) {

            dataset = new double[maxPassengers + 1][maxHour + 1];

            for (int i = 0; i <= maxPassengers; i++) {
                dataset[i] = getModeOccurrencePerHourAgainstMode(data,maxHour,i);
            }
        } else {

            // This loop gives the loop over all the different passenger groups, which is 1 in other cases.
            // In this case we have to group 0, 1 to 5, 6 to 10

            int bucketSize = getBucketSize();

            dataset = new double[5][maxHour + 1];


            // We need only 5 buckets
            // The modeOccurrentPerHour array index will not go beyond 5 as all the passengers will be
            // accomodated within the 4 buckets because the index will not be incremented until all
            // passengers falling in one bucket are added into that index of modeOccurrencePerHour
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            int bucket = 0;

            for (int i = 0; i <= maxPassengers; i++) {

                modeOccurrencePerHour = getModeOccurrenceOfPassengerWithBucketSize(data,modeOccurrencePerHour,maxHour,i);
                if (i == 0 || (i % bucketSize == 0) || i == maxPassengers) {

                    dataset[bucket] = modeOccurrencePerHour;
                    modeOccurrencePerHour = new double[maxHour + 1];
                    bucket = bucket + 1;
                }
            }
        }
        return dataset;
    }
    private CategoryDataset buildDeadHeadingDataForGraph(String mode){
        double[][] dataset=buildDeadHeadingDataset(this.deadHeadingsMap.get(mode),mode);
        return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
    }


    private void processModeChoice(Event event) {
        int hour = getEventHour(event.getTime());
        String mode = event.getAttributes().get("mode");
        modesChosen.add(mode);
        Map<String, Integer> hourData = hourModeFrequency.get(hour);
        Integer frequency = 1;
        if (hourData != null) {
            frequency = hourData.get(mode);
            if (frequency != null) {
                frequency++;
            }else{
                frequency = 1;
            }
        } else {
            hourData = new HashMap<>();
        }
        hourData.put(mode, frequency);
        hourModeFrequency.put(hour, hourData);
    }
    private void processFuelUsage(Event event) {

        int hour = getEventHour(event.getTime());


        String vehicleType = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
        String mode = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicleId = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        double lengthInMeters = Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_LENGTH));
        String fuelString = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_FUEL);

        modesFuel.add(mode);

        try {

            Double fuel = PathTraversalSpatialTemporalTableGenerator.getFuelConsumptionInMJ(vehicleId, mode, fuelString, lengthInMeters, vehicleType);
            ;

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
        } catch (Exception e) {

            e.printStackTrace();
        }
    }
    private void processDeadHeading(Event event) {

        int hour = getEventHour(event.getTime());
        String mode = event.getAttributes().get("mode");

        String vehicle_id = event.getAttributes().get("vehicle_id");
        String num_passengers = event.getAttributes().get("num_passengers");

        String graphName = mode;

        Map<Integer, Map<Integer, Integer>> deadHeadings = null;
        if (mode.equalsIgnoreCase("car") && vehicle_id.contains("ride")) {
            graphName = "tnc";
        }

        Integer _num_passengers = null;
        try {
            _num_passengers = Integer.parseInt(num_passengers);
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
        }

        boolean validCase = isValidCase(graphName, _num_passengers);

        if (validCase) {

            deadHeadings = deadHeadingsMap.get(graphName);

            Map<Integer, Integer> hourData = null;
            if (deadHeadings != null)
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

            if (deadHeadings == null) {
                deadHeadings = new HashMap<>();
            }
            deadHeadings.put(hour, hourData);

            deadHeadingsMap.put(graphName, deadHeadings);
        }

        if (graphName.equalsIgnoreCase("tnc")) {


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

    // helper methods
    private int getEventHour(double time) {
        return (int) time / SECONDS_IN_HOUR;
    }
    private int getBucketSize() {
        return (int) Math.ceil(maxPassengersSeenOnGenericCase / 4.0);
    }
    private boolean isValidCase(String graphName, int numPassengers) {
        boolean validCase = false;

        if (!graphName.equalsIgnoreCase("walk")) {
            if (graphName.equalsIgnoreCase("tnc") && numPassengers >= 0 && numPassengers <= TNC_MAX_PASSENGERS) {

                validCase = true;
            } else if (graphName.equalsIgnoreCase("car") && numPassengers >= 0 && numPassengers <= CAR_MAX_PASSENGERS) {
                validCase = true;
            } else {
                if (maxPassengersSeenOnGenericCase < numPassengers) maxPassengersSeenOnGenericCase = numPassengers;
                validCase = true;
            }
        }

        return validCase;
    }
    private String getLegendText(String graphName, int i) {

        int bucketSize = getBucketSize();
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
    private String getGraphFileName(String graphName) {
        if (graphName.equalsIgnoreCase("tnc")) {
            return "tnc_passenger_per_trip.png";
        } else if (graphName.equalsIgnoreCase("tnc_deadheading_distance")) {
            return "tnc_deadheading_distance.png";
        } else {
            return "passenger_per_trip_" + graphName + ".png" ;
        }
    }
    private String getTitle(String graphName) {
        if (graphName.equalsIgnoreCase("tnc")) {
            return "Number of Passengers per Trip [TNC]";
        } else if (graphName.equalsIgnoreCase("tnc_deadheading_distance")) {
            return "Dead Heading Distance [TNC]";
        } else if (graphName.equalsIgnoreCase("car")) {
            return "Number of Passengers per Trip [Car]";
        } else {
            return "Number of Passengers per Trip [" + graphName + "]";
        }
    }
    private Color getBarAndLegendColor(int i) {
        if (i < colors.size()) {
            return colors.get(i);
        } else {
            return getRandomColor();
        }
    }
    private Color getRandomColor() {
        Random rand = new Random();
        // Java 'Color' class takes 3 floats, from 0 to 1.
        float r = rand.nextFloat();
        float g = rand.nextFloat();
        float b = rand.nextFloat();
        Color randomColor = new Color(r, g, b);
        return randomColor;
    }

    public int getAvgCountForSpecificHour(String mode,int hour){
        double[][] dataset = buildAverageTimesDataset(mode);
        return (int)Math.ceil(dataset[0][hour]);
    }
    private CategoryDataset buildAverageTimesDatasetGraph(String mode){
        double[][] dataset = buildAverageTimesDataset(mode);
        return DatasetUtilities.createCategoryDataset(mode, "", dataset);

    }
    private double[][] buildAverageTimesDataset(String mode) {
        Map<Integer, List<Double>> times = hourlyPersonTravelTimes.get(mode);
        List<Integer> hoursList = new ArrayList<>();
        hoursList.addAll(times.keySet());
        Collections.sort(hoursList);

        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[1][maxHour + 1];

        double[] travelTimes = new double[maxHour + 1];
        for (int i = 0; i < maxHour; i++) {

            List<Double> hourData = times.get(i);
            Double average = 0d;
            if (hourData != null) {
                average = hourData.stream().mapToDouble(val -> val).average().getAsDouble();
            }
            travelTimes[i] = average;
        }

        dataset[0] = travelTimes;
        return dataset;
    }

}