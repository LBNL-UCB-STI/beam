package beam.analysis.plots;

import beam.analysis.IterationSummaryAnalysis;
import beam.sim.metrics.SimulationMetricCollector;
import com.google.common.base.CaseFormat;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PersonTravelTimeAnalysis implements GraphAnalysis, IterationSummaryAnalysis {

    private final Logger log = LoggerFactory.getLogger(PersonTravelTimeAnalysis.class);

    private static final int SECONDS_IN_MINUTE = 60;
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Average Travel Time [min]";
    private static final String otherMode = "mixed_mode";
    private static final String carMode = "car";
    public static final String fileBaseName = "averageTravelTimes";
    private final Map<String, Map<Id<Person>, PersonDepartureEvent>> personLastDepartureEvents = new HashMap<>();
    private final Map<String, Map<Integer, List<Double>>> hourlyPersonTravelTimes = new HashMap<>();
    private final List<Double> averageTime = new ArrayList<>();
    private final SimulationMetricCollector simMetricCollector;

    private final StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, Tuple<double[][], Double>>> statComputation;
    private final boolean writeGraph;
    private final OutputDirectoryHierarchy ioCotroller;

    public PersonTravelTimeAnalysis(SimulationMetricCollector simMetricCollector, StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, Tuple<double[][], Double>>> statComputation, boolean writeGraph, OutputDirectoryHierarchy ioCotroller) {
        this.statComputation = statComputation;
        this.writeGraph = writeGraph;
        this.simMetricCollector = simMetricCollector;
        this.ioCotroller = ioCotroller;
    }

    public static class PersonTravelTimeComputation implements StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, Tuple<double[][], Double>>> {

        /**
         * Computes the required stats from the given input.
         *
         * @param stat A mapping that maps travel mode -> ( mapping from hour of the day -> list of travel times recorded during that hour)
         * @return tuple (travel modes , (tuple of (averageTravelTimesByModeAndHour , averageTravelTimeInADayForCarMode)))
         */
        @Override
        public Tuple<List<String>, Tuple<double[][], Double>> compute(Map<String, Map<Integer, List<Double>>> stat) {
            // Extract the travel modes recorded and sort them in ascending order
            List<String> travelModes = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.keySet());
            // Extract the hours of the day recorded and sort them in order
            List<Integer> hoursList = stat.values().stream().flatMap(m -> m.keySet().stream()).sorted().collect(Collectors.toList());
            // Get the maximum hour value
            int maxHour = hoursList.get(hoursList.size() - 1);
            // A 2D matrix to store average travel times by mode and hour (rows = travel mode ; columns = hour of the day)
            double[][] averageTravelTimesByModeAndHour = new double[travelModes.size()][maxHour + 1];
            for (int i = 0; i < travelModes.size(); i++) {
                // compute the average travel times for each hour with the travel mode and save it to the respective row in data
                averageTravelTimesByModeAndHour[i] = buildAverageTimesDataset(stat.get(travelModes.get(i)));
            }
            // Calculate the average travel time for the entire day when the travel mode is CAR
            double dayAverageData = 0.0;
            if (stat.get(carMode) != null) {
                dayAverageData = buildDayAverageDataset(stat.get(carMode));
            }
            return new Tuple<>(travelModes, new Tuple<>(averageTravelTimesByModeAndHour, dayAverageData));
        }

        /**
         * Builds array data set with average travel time computed for each hour.
         *
         * @param travelTimesByHour A mapping from hour of the day -> list of travel times recorded during that hour
         * @return array representing average times for each hour (array index = hour of the day)
         */
        private double[] buildAverageTimesDataset(Map<Integer, List<Double>> travelTimesByHour) {
            // Extract and sort available hours in numeric order
            List<Integer> hoursList = new ArrayList<>(travelTimesByHour.keySet());
            Collections.sort(hoursList);
            // Get the maximum hour value
            int maxHour = hoursList.get(hoursList.size() - 1);
            // Declare an array to store the average travel times computed for each hour (where index = hour).
            double[] averageTravelTimesWithHourIndex = new double[maxHour + 1];
            // For each hour , now compute the average of all travel times available within that hour and
            // save the average value to the array
            for (int i = 0; i <= maxHour; i++) {
                List<Double> hourData = travelTimesByHour.get(i);
                averageTravelTimesWithHourIndex[i] = hourData == null
                        ? 0d
                        : hourData.stream().mapToDouble(val -> val).average().orElse(0.0);
            }
            return averageTravelTimesWithHourIndex;
        }

        /**
         * Calculates the average of travel times during the day
         *
         * @param travelTimesByHour Map that maps from hour of the day -> travel times during that hour
         * @return average travel time
         */
        private double buildDayAverageDataset(Map<Integer, List<Double>> travelTimesByHour) {
            Set<Integer> hourSet = travelTimesByHour.keySet();
            int count = 0;
            double time = 0d;
            for (Integer i : hourSet) {
                List<Double> hourData = travelTimesByHour.get(i);
                if (hourData != null) {
                    time += hourData.stream().mapToDouble(val -> val).sum();
                    count += hourData.size();
                }
            }

            return time / count;
        }
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE))
            processPersonDepartureEvent(event);
        else if (event instanceof PersonArrivalEvent || event.getEventType().equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE))
            processPersonArrivalEvent(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        // tuple (travel modes , (tuple of (averageTravelTimesByModeAndHour , averageTravelTimeInADayForCarMode)))
        Tuple<List<String>, Tuple<double[][], Double>> data = compute();
        List<String> modes = data.getFirst();
        double[][] averageTravelTimesByModeAndHour = data.getSecond().getFirst();
        double averageTravelTimeInADayForCarMode = data.getSecond().getSecond();
        averageTime.add(averageTravelTimeInADayForCarMode);

        HashMap<String, String> tags = new HashMap<>();
        tags.put("mode", "car");
        simMetricCollector.writeGlobalJava("average-travel-time", averageTravelTimeInADayForCarMode, tags, false);

        if (writeGraph) {
            for (int i = 0; i < modes.size(); i++) {
                CategoryDataset averageDataset = GraphUtils.createCategoryDataset(modes.get(i), "", averageTravelTimesByModeAndHour[i]);
                createAverageTimesGraph(averageDataset, event.getIteration(), modes.get(i));
            }
            createNonArrivalAgentAtTheEndOfSimulationGraph(event.getIteration());
        }

        createNonArrivalAgentAtTheEndOfSimulationCSV(event.getIteration());
        createCSV(data, event.getIteration());
    }

    Tuple<List<String>, Tuple<double[][], Double>> compute() {
        return statComputation.compute(hourlyPersonTravelTimes);
    }

    private void createCSV(Tuple<List<String>, Tuple<double[][], Double>> data, int iteration) {
        List<String> modes = data.getFirst();
        double[][] dataSets = data.getSecond().getFirst();
        String csvFileName = ioCotroller.getIterationFilename(iteration, fileBaseName + ".csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            StringBuilder heading = new StringBuilder("TravelTimeMode\\Hour");
            int hours = Arrays.stream(dataSets).mapToInt(value -> value.length).max().orElse(dataSets[0].length);
            for (int hour = 0; hour <= hours; hour++) {
                heading.append(",").append(hour);
            }
            out.write(heading.toString());
            out.newLine();

            for (int category = 0; category < dataSets.length; category++) {
                out.write(modes.get(category));
                double[] categories = dataSets[category];
                for (double inner : categories) {
                    out.write("," + inner);
                }
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("Error in Average Travel Time CSV generation", e);
        }
    }

    @Override
    public void resetStats() {
        personLastDepartureEvents.clear();
        hourlyPersonTravelTimes.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {

        return hourlyPersonTravelTimes.entrySet().stream().collect(Collectors.toMap(
                e -> "personTravelTime_" + e.getKey(),

                e -> e.getValue() != null ? e.getValue().values().stream().flatMapToDouble(times -> times.stream().mapToDouble(Double::doubleValue)).sum() : 0
        ));
    }

    private void processPersonArrivalEvent(Event event) {
        // Get person id and travel mode from the PersonArrivalEvent
        PersonArrivalEvent personArrivalEvent = (PersonArrivalEvent) event;
        Id<Person> personId = personArrivalEvent.getPersonId();
        String mode = personArrivalEvent.getLegMode();
        if (!mode.equalsIgnoreCase("car")) {
            // personLastDepartureEvents(map) : travel mode -> (person -> previous departure event)
            // Get the list of previous departures for the current arrival mode
            Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
            // case : if previous departure events are available
            if (departureEvents != null) {
                // Get the departure event corresponding to the current person
                PersonDepartureEvent personDepartureEvent = departureEvents.get(personId);
                if (personDepartureEvent != null) {
                    // Get the hour of the departure event
                    int basketHour = GraphsStatsAgentSimEventsListener.getEventHour(personDepartureEvent.getTime());
                    // Compute the travel travel time = current arrival time - previous departure time
                    Double travelTime = (personArrivalEvent.getTime() - personDepartureEvent.getTime()) / SECONDS_IN_MINUTE;
                    // hourlyPersonTravelTimes(map) : travel mode -> (hour of the day -> travel time)
                    Map<Integer, List<Double>> hourlyPersonTravelTimesPerMode = hourlyPersonTravelTimes.get(mode);
                    if (hourlyPersonTravelTimesPerMode == null) {
                        // if this is the first event , initiate and add the current travel time to the list of travel times
                        hourlyPersonTravelTimesPerMode = new HashMap<>();
                        List<Double> travelTimes = new ArrayList<>();
                        travelTimes.add(travelTime);
                        hourlyPersonTravelTimesPerMode.put(basketHour, travelTimes);
                    } else {
                        // if not the first event , fetch the previously tracked travel times and add the current travel time to the list
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
                    // remove the previous departure event tracked from the current person and update the map
                    departureEvents.remove(personId);
                    personLastDepartureEvents.put(mode, departureEvents);
                } else {
                    // case : if no previous departures available for this person (or probably this is the first departure)
                    Set<String> modeSet = personLastDepartureEvents.keySet();
                    String selectedMode = null;
                    // For each possible modes , check the last departure event tracked for the current person and select the mode as well
                    //Modeset is very small list hence we can iterate them
                    for (String mayBeMode : modeSet) {
                        Map<Id<Person>, PersonDepartureEvent> lastDepartureEvents = personLastDepartureEvents.get(mayBeMode);
                        if (lastDepartureEvents.get(personId) != null) {
                            personDepartureEvent = lastDepartureEvents.get(personId);
                            selectedMode = mayBeMode;
                            break;
                        }
                    }
                    if (personDepartureEvent != null) {
                        int basketHour = GraphsStatsAgentSimEventsListener.getEventHour(personDepartureEvent.getTime());
                        Double travelTime = (personArrivalEvent.getTime() - personDepartureEvent.getTime()) / SECONDS_IN_MINUTE;
                        Map<Integer, List<Double>> hourlyPersonTravelTimesPerMode = hourlyPersonTravelTimes.get(otherMode);
                        if (hourlyPersonTravelTimesPerMode == null) {
                            hourlyPersonTravelTimesPerMode = new HashMap<>();
                            List<Double> travelTimes = new ArrayList<>();
                            travelTimes.add(travelTime);
                            hourlyPersonTravelTimesPerMode.put(basketHour, travelTimes);
                        } else {
                            List<Double> travelTimes = hourlyPersonTravelTimesPerMode.get(basketHour);
                            if (travelTimes == null) {
                                travelTimes = new ArrayList<>();
                            }
                            travelTimes.add(travelTime);
                            hourlyPersonTravelTimesPerMode.put(basketHour, travelTimes);
                        }
                        hourlyPersonTravelTimes.put(otherMode, hourlyPersonTravelTimesPerMode);
                        Map<Id<Person>, PersonDepartureEvent> departureEventsList = personLastDepartureEvents.get(selectedMode);
                        departureEventsList.remove(personId);
                        personLastDepartureEvents.put(selectedMode, departureEventsList);
                    }
                }
            }
        }
    }

    /**
     * Processes the current person departure event and tracks the departure details for further processing.
     *
     * @param event Person Departure Event
     */
    private void processPersonDepartureEvent(Event event) {
        PersonDepartureEvent personDepartureEvent = (PersonDepartureEvent) event;
        if (!personDepartureEvent.getLegMode().equalsIgnoreCase("car")) {
            // Extract the mode of the departure event
            String mode = personDepartureEvent.getLegMode();
            // Get the list of previous departures tracked for this mode
            Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
            // Add/update the entry : current person -> current departure event
            if (departureEvents == null) {
                departureEvents = new HashMap<>();
            }
            departureEvents.put(personDepartureEvent.getPersonId(), personDepartureEvent);
            // Add/update the entry : mode -> (person -> previous departure event)
            personLastDepartureEvents.put(mode, departureEvents);
        }
    }

    private void createAverageTimesGraph(CategoryDataset dataset, int iterationNumber, String mode) throws IOException {
        String fileName = fileBaseName + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, mode) + ".png";
        String graphTitle = "Average Travel Time [" + mode + "]";

        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, false);
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, dataset.getRowCount());
        String graphImageFile = ioCotroller.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void createNonArrivalAgentAtTheEndOfSimulationGraph(int iterationNumber) throws IOException {
        DefaultCategoryDataset defaultCategoryDataset = new DefaultCategoryDataset();
        personLastDepartureEvents.keySet().forEach(m -> defaultCategoryDataset.addValue((Number) personLastDepartureEvents.get(m).size(), 0, m));
        String graphTitle = "Non Arrived Agents at End of Simulation";

        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(defaultCategoryDataset, graphTitle, "modes", "count", false);
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, defaultCategoryDataset.getRowCount());
        String graphImageFile = ioCotroller.getIterationFilename(iterationNumber, "NonArrivedAgentsAtTheEndOfSimulation.png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void createNonArrivalAgentAtTheEndOfSimulationCSV(int iterationNumber) {
        String csvFileName = ioCotroller.getIterationFilename(iterationNumber, "NonArrivedAgentsAtTheEndOfSimulation.csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            String heading = "modes,count";
            out.write(heading);
            out.newLine();
            Set<String> modes = personLastDepartureEvents.keySet();
            for (String mode : modes) {
                Map<Id<Person>, PersonDepartureEvent> personDepartureEventMap = personLastDepartureEvents.get(mode);
                out.append(mode).append(",").append(String.valueOf(personDepartureEventMap.size()));
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("Error in Non Arrival Agent CSV generation", e);
        }
    }
}
