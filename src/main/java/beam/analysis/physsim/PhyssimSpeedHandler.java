package beam.analysis.physsim;

import beam.analysis.plots.GraphUtils;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.sim.config.BeamConfig;
import beam.utils.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PhyssimSpeedHandler implements PersonArrivalEventHandler, PersonDepartureEventHandler {
    private final Logger log = LoggerFactory.getLogger(PhyssimSpeedHandler.class);

    private final String fileName = "MultiJDEQSim_speed";
    private final Map<Id<Person>,? extends Person> persons;
    private final Map<String, PersonDepartureEvent> personsDepartureTime = new HashMap<>();
    private final Map<Integer, Mean> binSpeed = new HashMap<>();
    private final OutputDirectoryHierarchy controlerIO;
    private final int binSize;

    public PhyssimSpeedHandler(Population population, OutputDirectoryHierarchy controlerIO, BeamConfig beamConfig){
        persons = population.getPersons();
        binSize =  beamConfig.beam().physsim().linkStatsBinSize();
        this.controlerIO = controlerIO;
    }


    /*
     *   checking all selected plans and taking selected leg between 2 activities if departure_event_link_id is equal to activity start and
     *   arrival_event_link_id is equal to next consecutive activity
     * */
    @Override
    public void handleEvent(PersonArrivalEvent arrivalEvent) {
        String personId = arrivalEvent.getPersonId().toString();
        if(isBus(personId)) {
            return;
        }
        PersonDepartureEvent departureEvent = personsDepartureTime.remove(personId);
        if(departureEvent != null){
            double travelTime = arrivalEvent.getTime() - departureEvent.getTime();
            Plan selectedPlan = persons.get(arrivalEvent.getPersonId()).getSelectedPlan();
            List<PlanElement> planElements = selectedPlan.getPlanElements();

            for(PlanElement planElement: planElements){
                if(planElement instanceof Activity) {
                    Activity activity = (Activity) planElement;

                    if(activity.getLinkId().equals(departureEvent.getLinkId())) {

                        int index = planElements.indexOf(planElement);
                        if(index + 2 <= planElements.size()) {
                            PlanElement nextPlanElement = planElements.get(index + 2);
                            if (nextPlanElement instanceof Activity) {
                                Activity nextActivity = (Activity) nextPlanElement;

                                if (nextActivity.getLinkId().equals(arrivalEvent.getLinkId())) {
                                    PlanElement legElement = planElements.get(index + 1);
                                    if (legElement instanceof Leg) {
                                        Leg leg = (Leg) legElement;
                                        double distance = leg.getRoute().getDistance();
                                        // some leg with distance > 0 can have travel time = 0 for JDEQ
                                        // and travel time ~= 0 for BPR, this gives us huge speed
                                        if (travelTime > 0.01) {
                                            double speed = distance / travelTime;
                                            int bin = (int) departureEvent.getTime() / binSize;
                                            Mean mean = binSpeed.computeIfAbsent(bin, i -> new Mean());
                                            mean.increment(speed);
                                        }
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isBus(String personId){
        return personId.contains(":");
    }

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        String personId = event.getPersonId().toString();
        if(!isBus(personId)){
            personsDepartureTime.put(personId, event);
        }
    }

    public void notifyIterationEnds(int iteration) {
        double[] data = buildData();
        writeIterationGraph(iteration, data);
        writeIterationCsv(iteration, data);
        personsDepartureTime.clear();
        binSpeed.clear();
    }

    private double[] buildData() {
        int maxHour = binSpeed.isEmpty() ? 24 : Collections.max(binSpeed.keySet());
        double[] data = new double[maxHour + 1];

        for(int bin=0; bin <= maxHour; bin++){
            if(binSpeed.containsKey(bin)) {
                data[bin] = binSpeed.get(bin).getResult();
            } else {
                data[bin] = 0.0;
            }
        }

        return data;
    }

    private void writeIterationGraph(int iteration, double[] data) {
        CategoryDataset dataSet = GraphUtils.createCategoryDataset("car", "", data);
        createIterationGraphForAverageSpeed(dataSet, iteration);
    }

    private void createIterationGraphForAverageSpeed(CategoryDataset dataset, int iteration) {
        String graphTitle = "Average Speed";
        JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(
                dataset,
                graphTitle,
                "hour",
                "Average Speed [m/s]",
                false
        );
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, dataset.getRowCount());
        String graphImageFile = controlerIO.getIterationFilename(iteration, fileName+".png");
        try{
            GraphUtils.saveJFreeChartAsPNG(
                    chart,
                    graphImageFile,
                    GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
                    GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
            );
        }
        catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private void writeIterationCsv(int iteration, double[] data) {
        String path = controlerIO.getIterationFilename(iteration, fileName+".csv");

        List<String> rows = IntStream.range(0, data.length)
                .mapToObj(i-> i + "," + data[i])
                .collect(Collectors.toList());

        FileUtils.writeToFile(path, Option.apply("timeBin,averageSpeed"), StringUtils.join(rows, "\n"), Option.empty());
    }
}
