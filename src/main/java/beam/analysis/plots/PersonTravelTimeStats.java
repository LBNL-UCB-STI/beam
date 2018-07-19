package beam.analysis.plots;

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

import java.io.IOException;
import java.util.*;

public class PersonTravelTimeStats implements IGraphStats{
    private static Map<String, Map<Id<Person>, PersonDepartureEvent>> personLastDepartureEvents = new HashMap<>();
    private static Map<String, Map<Integer, List<Double>>> hourlyPersonTravelTimes = new HashMap<>();
    private static final int SECONDS_IN_MINUTE = 60;
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Average Travel Time [min]";
    private String fileName = null;
    private String graphTitle = null;


    @Override
    public void processStats(Event event) {
        if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE))
            processPersonDepartureEvent(event);
        else if (event instanceof PersonArrivalEvent || event.getEventType().equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE))
            processPersonArrivalEvent(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        for (String mode : hourlyPersonTravelTimes.keySet()) {
            CategoryDataset averageDataset = buildAverageTimesDatasetGraph( mode);
            createAverageTimesGraph(averageDataset, event.getIteration(), mode);
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {

    }

    @Override
    public void resetStats() {
        personLastDepartureEvents.clear();
        hourlyPersonTravelTimes.clear();
    }

    public int getAvgCountForSpecificHour(String mode,int hour){
        double[][] dataset = buildAverageTimesDataset(mode);
        return (int)Math.ceil(dataset[0][hour]);
    }

    private void processPersonArrivalEvent(Event event){
        String mode = ((PersonArrivalEvent) event).getLegMode();

        Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
        if (departureEvents != null) {
            PersonArrivalEvent personArrivalEvent = (PersonArrivalEvent) event;
            Id<Person> personId = personArrivalEvent.getPersonId();
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
                personLastDepartureEvents.remove(personId);
            }
        }
    }
    private void processPersonDepartureEvent(Event event){
        PersonDepartureEvent personDepartureEvent = (PersonDepartureEvent) event;

        String mode = ((PersonDepartureEvent) event).getLegMode();
        Map<Id<Person>, PersonDepartureEvent> departureEvents = personLastDepartureEvents.get(mode);
        if (departureEvents == null) {
            departureEvents = new HashMap<>();
        }
        departureEvents.put(((PersonDepartureEvent) event).getPersonId(), personDepartureEvent);
        personLastDepartureEvents.put(mode, departureEvents);
    }
    private void createAverageTimesGraph(CategoryDataset dataset, int iterationNumber, String mode) throws IOException {
        fileName = "average_travel_times_" + mode + ".png";
        graphTitle = "Average Travel Time [" + mode + "]";
        boolean legend = false;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot,dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private CategoryDataset buildAverageTimesDatasetGraph(String mode){
        double[][] dataset = buildAverageTimesDataset(mode);
        return DatasetUtilities.createCategoryDataset(mode, "", dataset);

    }
    private double[][] buildAverageTimesDataset(String mode) {
        Map<Integer, List<Double>> times = hourlyPersonTravelTimes.get(mode);
        List<Integer> hoursList = new ArrayList<>(times.keySet());
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
