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
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import scala.Option;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PhyssimSpeedHandler implements PersonArrivalEventHandler, PersonDepartureEventHandler {

    private final Map<Id<Person>,? extends Person> persons;
    private final Map<Id<Person>, Double> personsDepartureTime = new HashMap<>();
    private final Map<Integer, List<Double>> binSpeed = new HashMap<>();
    private final OutputDirectoryHierarchy controlerIO;
    private final int binSize;


    public PhyssimSpeedHandler(Population population, OutputDirectoryHierarchy controlerIO, BeamConfig beamConfig){
        persons = population.getPersons();
        binSize =  beamConfig.beam().physsim().linkStatsBinSize();
        this.controlerIO = controlerIO;
    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {
        Id<Person> personId = event.getPersonId();
        if(personsDepartureTime.containsKey(personId)){
            double personDepartureTime = personsDepartureTime.get(personId);
            double travelTime = event.getTime() - personDepartureTime;
            Person person = persons.get(personId);
            Plan selectedPlan = person.getSelectedPlan();
            System.out.println(selectedPlan);
            Optional<Double> distance = selectedPlan.getPlanElements().stream()
                    .filter(planElement -> planElement instanceof Leg)
                    .map(leg -> ((Leg)leg).getRoute().getDistance()).reduce(Double::sum);

            if(distance.isPresent() && travelTime > 0.0) {
                double speed = distance.get() / travelTime;
                int bin = (int)personDepartureTime / binSize;
                binSpeed.merge(bin, Lists.newArrayList(speed), ListUtils::union);
            }
        }
    }

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        personsDepartureTime.put(event.getPersonId(), event.getTime());
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

        for(int bin=1; bin <= maxHour; bin++){
            if(binSpeed.containsKey(bin)){
                data[0][bin-1] = binSpeed.get(bin).stream().mapToDouble(x -> x).average().getAsDouble();
            }else {
                data[0][bin] = 0.0;
            }
        }

        CategoryDataset dataSet = DatasetUtilities.createCategoryDataset("car", "", data);
        createIterationGraphForAverageSpeed(dataSet, iteration);
    }

    private void createIterationGraphForAverageSpeed(CategoryDataset dataset, int iteration) {
        String fileName = "MultiJDEQSim_speed.png";
        String graphTitle = "Average Speed";
        JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(
                dataset,
                graphTitle,
                "hour",
                "Average Speed [m/s]",
                fileName,
                false
        );
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, dataset.getRowCount());
        String graphImageFile = controlerIO.getIterationFilename(iteration, fileName);
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
        String fileName = controlerIO.getIterationFilename(iteration, "MultiJDEQSim_speed.csv");

        List<String> rows = binSpeed.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey()+","+entry.getValue().stream().mapToDouble(x -> x).average().getAsDouble())
                .collect(Collectors.toList());

        FileUtils.writeToFile(fileName, Option.apply("timeBin,averageSpeed"), StringUtils.join(rows, "\n"), Option.empty());
    }
}
