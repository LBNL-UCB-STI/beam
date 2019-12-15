package beam.analysis.plots;

import beam.analysis.IterationSummaryAnalysis;
import com.google.common.base.CaseFormat;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
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
    private static final String xAxisRootTitle = "Iteration";
    private static final String yAxisTitle = "Average Travel Time [min]";
    private static final String otherMode = "mixed_mode";
    private static final String carMode = "car";
    public static String fileBaseName = "averageTravelTimes";
    private final String fileNameForRoot = "averageCarTravelTimes";
    private Map<String, Map<Id<Person>, PersonDepartureEvent>> personLastDepartureEvents = new HashMap<>();
    private Map<String, Map<Integer, List<Double>>> hourlyPersonTravelTimes = new HashMap<>();
    private List<Double> averageTime = new ArrayList<>();

    private final StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, Tuple<double[][], Double>>> statComputation;
    private final boolean writeGraph;

    public PersonTravelTimeAnalysis(StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, Tuple<double[][], Double>>> statComputation, boolean writeGraph) {
        this.statComputation = statComputation;
        this.writeGraph = writeGraph;
    }

    public static class PersonTravelTimeComputation implements StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, Tuple<double[][], Double>>> {

        @Override
        public Tuple<List<String>, Tuple<double[][], Double>> compute(Map<String, Map<Integer, List<Double>>> stat) {
            List<String> modeKeys = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.keySet());
            List<Integer> hoursList = stat.values().stream().flatMap(m -> m.keySet().stream()).sorted().collect(Collectors.toList());
            int maxHour = hoursList.get(hoursList.size() - 1);
            double[][] data = new double[modeKeys.size()][maxHour + 1];
            for (int i = 0; i < modeKeys.size(); i++) {
                data[i] = buildAverageTimesDataset(stat.get(modeKeys.get(i)));
            }
            double dayAverageData = 0.0;
            if (stat.get(carMode) != null) {
                dayAverageData = buildDayAverageDataset(stat.get(carMode));
            }
            return new Tuple<>(modeKeys, new Tuple<>(data, dayAverageData));
        }

        private double[] buildAverageTimesDataset(Map<Integer, List<Double>> times) {
            List<Integer> hoursList = new ArrayList<>(times.keySet());
            Collections.sort(hoursList);

            int maxHour = hoursList.get(hoursList.size() - 1);
            double[] travelTimes = new double[maxHour + 1];
            for (int i = 0; i <= maxHour; i++) {

                List<Double> hourData = times.get(i);
                Double average = 0d;
                if (hourData != null) {
                    average = hourData.stream().mapToDouble(val -> val).average().orElse(0.0);
                }
                travelTimes[i] = average;
            }
            return travelTimes;
        }

        private double buildDayAverageDataset(Map<Integer, List<Double>> times) {
            Set<Integer> hourSet = times.keySet();
            int count = 0;
            double time = 0d;
            for (Integer i : hourSet) {
                List<Double> hourData = times.get(i);
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
        Tuple<List<String>, Tuple<double[][], Double>> data = compute();
        List<String> modes = data.getFirst();
        double[][] dataSets = data.getSecond().getFirst();
        averageTime.add(data.getSecond().getSecond());

        if (writeGraph) {
            for (int i = 0; i < modes.size(); i++) {
                double[][] singleDataSet = new double[1][dataSets[i].length];
                singleDataSet[0] = dataSets[i];
                CategoryDataset averageDataset = buildAverageTimesDatasetGraph(modes.get(i), singleDataSet);
                createAverageTimesGraph(averageDataset, event.getIteration(), modes.get(i));
            }
            createRootGraphForAverageCarTravelTime(event);
            createNonArrivalAgentAtTheEndOfSimulationGraph(event.getIteration());
        }

        createNonArrivalAgentAtTheEndOfSimulationCSV(event.getIteration());
        createCSV(data, event.getIteration());
        createRootCSVForAverageCarTravelTime(event);
    }

    public void createRootGraphForAverageCarTravelTime(IterationEndsEvent event) throws IOException {
        double[][] singleCarDataSet = new double[1][event.getIteration() + 1];
        for (int i = 0; i <= event.getIteration(); i++) {
            singleCarDataSet[0][i] = averageTime.get(i);
        }
        CategoryDataset averageCarDatasetForRootIteration = buildAverageTimeDatasetGraphForRoot(carMode, singleCarDataSet);
        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();
        String fileName = outputDirectoryHierarchy.getOutputFilename(fileNameForRoot + ".png");
        createCarAverageTimesGraphForRootIteration(averageCarDatasetForRootIteration, carMode, fileName);
    }

    Tuple<List<String>, Tuple<double[][], Double>> compute() {
        return statComputation.compute(hourlyPersonTravelTimes);
    }

    private void createRootCSVForAverageCarTravelTime(IterationEndsEvent event) {
        int currentIteration = event.getIteration();
        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();
        String csvFileName = outputDirectoryHierarchy.getOutputFilename(fileNameForRoot + ".csv");

        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            out.write("iteration,averageCarTravelTime");
            out.newLine();

            for (int i = 0; i <= currentIteration; i++) {
                double averageCarTravelTime = averageTime.get(i);
                out.write(i + "," + averageCarTravelTime);
                out.newLine();
            }

            out.flush();
        } catch (IOException e) {
            log.error("Error in Average Travel Time CSV generation", e);
        }
    }

    private void createCSV(Tuple<List<String>, Tuple<double[][], Double>> data, int iteration) {
        List<String> modes = data.getFirst();
        double[][] dataSets = data.getSecond().getFirst();
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iteration, fileBaseName + ".csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            StringBuilder heading = new StringBuilder("TravelTimeMode\\Hour");
            int hours = Arrays.stream(dataSets).mapToInt(value -> value.length).max().orElse(dataSets[0].length);
            for (int hour = 1; hour <= hours; hour++) {
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
        PersonArrivalEvent personArrivalEvent = (PersonArrivalEvent) event;
        Id<Person> personId = personArrivalEvent.getPersonId();
        String mode = personArrivalEvent.getLegMode();

        Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
        if (departureEvents != null) {
            PersonDepartureEvent personDepartureEvent = departureEvents.get(personId);
            if (personDepartureEvent != null) {
                int basketHour = GraphsStatsAgentSimEventsListener.getEventHour(personDepartureEvent.getTime());
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
                departureEvents.remove(personId);
                personLastDepartureEvents.put(mode, departureEvents);
            } else {
                Set<String> modeSet = personLastDepartureEvents.keySet();
                String selectedMode = null;
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
                            travelTimes.add(travelTime);
                        } else {
                            travelTimes.add(travelTime);
                        }
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

    private void processPersonDepartureEvent(Event event) {
        PersonDepartureEvent personDepartureEvent = (PersonDepartureEvent) event;

        String mode = personDepartureEvent.getLegMode();
        Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
        if (departureEvents == null) {
            departureEvents = new HashMap<>();
        }
        departureEvents.put(personDepartureEvent.getPersonId(), personDepartureEvent);
        personLastDepartureEvents.put(mode, departureEvents);
    }

    private void createAverageTimesGraph(CategoryDataset dataset, int iterationNumber, String mode) throws IOException {
        String fileName = fileBaseName + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, mode) + ".png";
        String graphTitle = "Average Travel Time [" + mode + "]";

        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, false);
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void createNonArrivalAgentAtTheEndOfSimulationGraph(int iterationNumber) throws IOException {
        DefaultCategoryDataset defaultCategoryDataset = new DefaultCategoryDataset();
        personLastDepartureEvents.keySet().forEach(m -> defaultCategoryDataset.addValue((Number) personLastDepartureEvents.get(m).size(), 0, m));
        String graphTitle = "Non Arrived Agents at End of Simulation";

        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(defaultCategoryDataset, graphTitle, "modes", "count", "NonArrivedAgentsAtTheEndOfSimulation.png", false);
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, defaultCategoryDataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "NonArrivedAgentsAtTheEndOfSimulation.png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void createNonArrivalAgentAtTheEndOfSimulationCSV(int iterationNumber) throws IOException {
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "NonArrivedAgentsAtTheEndOfSimulation.csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            String heading = "modes,count";
            out.write(heading);
            out.newLine();
            Set<String> modes = personLastDepartureEvents.keySet();
            for(String mode: modes){
                Map<Id<Person>, PersonDepartureEvent> personDepartureEventMap = personLastDepartureEvents.get(mode);
                out.write(mode+","+personDepartureEventMap.size());
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("Error in Non Arrival Agent CSV generation", e);
        }

    }

    private void createCarAverageTimesGraphForRootIteration(CategoryDataset dataset, String mode, String fileName) throws IOException {
        String graphTitle = "Average Travel Time [" + mode + "]";
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisRootTitle, yAxisTitle, fileName, false);
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, dataset.getRowCount());
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }


    private CategoryDataset buildAverageTimesDatasetGraph(String mode, double[][] dataset) {
        return DatasetUtilities.createCategoryDataset(mode, "", dataset);

    }

    private CategoryDataset buildAverageTimeDatasetGraphForRoot(String mode, double[][] dataset) {
        return createCategoryRootDataset(mode, "", dataset);
    }

    private static CategoryDataset createCategoryRootDataset(String rowKeyPrefix, String columnKeyPrefix, double[][] data) {
        DefaultCategoryDataset result = new DefaultCategoryDataset();
        for (int r = 0; r < data.length; r++) {
            String rowKey = rowKeyPrefix + (r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + (c);
                result.addValue(new Double(data[r][c]), rowKey, columnKey);
            }
        }
        return result;
    }
}
