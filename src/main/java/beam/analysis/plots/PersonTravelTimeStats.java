package beam.analysis.plots;

import com.google.common.base.CaseFormat;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PersonTravelTimeStats implements BeamStats, IterationSummaryStats {
    private static final int SECONDS_IN_MINUTE = 60;
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Average Travel Time [min]";
    private static final String otherMode = "others";
    private Map<String, Map<Id<Person>, PersonDepartureEvent>> personLastDepartureEvents = new HashMap<>();
    private Map<String, Map<Integer, List<Double>>> hourlyPersonTravelTimes = new HashMap<>();

    private final StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, double[][]>> statComputation;

    public PersonTravelTimeStats(StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, double[][]>> statComputation) {
        this.statComputation = statComputation;
    }

    public static class PersonTravelTimeComputation implements StatsComputation<Map<String, Map<Integer, List<Double>>>, Tuple<List<String>, double[][]>> {

        @Override
        public Tuple<List<String>, double[][]> compute(Map<String, Map<Integer, List<Double>>> stat) {
            List<String> modeKeys = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.keySet());
            List<Integer> hoursList = stat.values().stream().flatMap(m -> m.keySet().stream()).sorted().collect(Collectors.toList());
            int maxHour = hoursList.get(hoursList.size() - 1);
            double[][] data = new double[modeKeys.size()][maxHour + 1];
            for (int i = 0; i < modeKeys.size(); i++) {
                data[i] = buildAverageTimesDataset(stat.get(modeKeys.get(i)));
            }
            return new Tuple<>(modeKeys, data);
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
        Tuple<List<String>, double[][]> data = compute();
        List<String> modes = data.getFirst();
        double[][] dataSets = data.getSecond();
        for (int i = 0; i < modes.size(); i++) {
            double[][] singleDataSet = new double[1][dataSets[i].length];
            singleDataSet[0] = dataSets[i];
            CategoryDataset averageDataset = buildAverageTimesDatasetGraph(modes.get(i), singleDataSet);
            createAverageTimesGraph(averageDataset, event.getIteration(), modes.get(i));
        }
        createCSV(data, event.getIteration());
    }

    Tuple<List<String>, double[][]> compute() {
        return statComputation.compute(hourlyPersonTravelTimes);
    }

    private void createCSV(Tuple<List<String>, double[][]> data, int iteration) {
        List<String> modes = data.getFirst();
        double[][] dataSets = data.getSecond();
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iteration, "averageTravelTimes.csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            StringBuilder heading = new StringBuilder("TravelTimeMode\\Hour");
            int hours = Arrays.stream(dataSets).mapToInt(value -> value.length).max().orElse(dataSets[0].length);
            for (int hour = 1; hour <= hours ; hour++) {
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
            e.printStackTrace();
        }
    }

    @Override
    public void resetStats() {
        personLastDepartureEvents.clear();
        hourlyPersonTravelTimes.clear();
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {

        return hourlyPersonTravelTimes.entrySet().stream().collect(Collectors.toMap(
                e -> "personTravelTime_" + e.getKey().toString(),
                e -> e.getValue().values().stream().flatMapToDouble(
                        times -> times.stream().mapToDouble(Double::doubleValue)
                ).sum()
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
            }
            else {
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
        String fileName = "averageTravelTimes" + (mode.substring(0, 1).toUpperCase() + mode.substring(1)).replaceAll("_","") + ".png";
        String graphTitle = "Average Travel Time [" + mode + "]";

        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, false);
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private CategoryDataset buildAverageTimesDatasetGraph(String mode, double[][] dataset) {
        return DatasetUtilities.createCategoryDataset(mode, "", dataset);

    }

}
