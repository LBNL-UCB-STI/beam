package beam.analysis.physsim;

import beam.analysis.plots.GraphUtils;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.sim.config.BeamConfig;
import beam.utils.FileUtils;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
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
import scala.Option;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PhyssimSpeedHandler implements PersonArrivalEventHandler, PersonDepartureEventHandler {

    private final String fileName = "MultiJDEQSim_speed";
    private final Map<Id<Person>,? extends Person> persons;
    private final Map<String, PersonDepartureEvent> personsDepartureTime = new HashMap<>();
    private final Map<Integer, List<Double>> binSpeed = new HashMap<>();
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
                                        if(travelTime > 0.0) {
                                            double speed = distance / travelTime;
                                            int bin = (int) departureEvent.getTime() / binSize;
                                            binSpeed.merge(bin, Lists.newArrayList(speed), ListUtils::union);
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

        writeIterationGraph(iteration);
        writeIterationCsv(iteration);
        personsDepartureTime.clear();
        binSpeed.clear();
    }

    private void writeIterationGraph(int iteration) {
        int maxHour = Collections.max(binSpeed.keySet());
        double[][] data = new double[1][maxHour + 1];

        for(int bin=0; bin <= maxHour; bin++){
            if(binSpeed.containsKey(bin)){
                data[0][bin] = binSpeed.get(bin).stream().mapToDouble(x -> x).average().getAsDouble();
            }else {
                data[0][bin] = 0.0;
            }
        }

        CategoryDataset dataSet = DatasetUtilities.createCategoryDataset("car", "", data);
        createIterationGraphForAverageSpeed(dataSet, iteration);
    }

    private void createIterationGraphForAverageSpeed(CategoryDataset dataset, int iteration) {
        String graphTitle = "Average Speed";
        JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(
                dataset,
                graphTitle,
                "hour",
                "Average Speed [m/s]",
                fileName+".png",
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
        catch (IOException exception){
            exception.printStackTrace();
        }

    }

    private void writeIterationCsv(int iteration) {
        String path = controlerIO.getIterationFilename(iteration, fileName+".csv");

        List<String> rows = binSpeed.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> (entry.getKey()+1)+","+entry.getValue().stream().mapToDouble(x -> x).average().getAsDouble())
                .collect(Collectors.toList());

        FileUtils.writeToFile(path, Option.apply("timeBin,averageSpeed"), StringUtils.join(rows, "\n"), Option.empty());
    }
}
