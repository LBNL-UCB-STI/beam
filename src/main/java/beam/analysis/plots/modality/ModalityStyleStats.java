package beam.analysis.plots.modality;

import beam.analysis.plots.GraphUtils;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.*;

public class ModalityStyleStats {
    private Set<String> className ;
    private Map<Integer, Map<String, Double>> iterationVsModalityClassCount ;
    private final String graphTile;
    private final String xAxisTitle;
    private final String yAxisTitle;
    private final String fileName;
    private final String attributeName;

    public ModalityStyleStats(){
        className = new TreeSet<>();
        iterationVsModalityClassCount = new HashMap<>();
        graphTile = "Modality Style";
        xAxisTitle = "Iteration";
        yAxisTitle = "Number of Agents";
        fileName = "modality-style.png";
        attributeName = "modality-style";
    }

    public void buildModalityStyleGraph() {
        try {
            buildGraphFromPopulationProcessDataSet();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void processData(Population population, IterationEndsEvent event) {
        processPopulationPlan(population, event);
    }


    private void processPopulationPlan(Population population, IterationEndsEvent event) {
        if (population == null) {
            return;
        }

        Set entries = population.getPersons().keySet();
        for (Object entry : entries) {
            String key = entry.toString();
            Person person = population.getPersons().get(Id.createPersonId(key));
            Plan plan = person.getSelectedPlan();
            String modalityStyle = plan.getAttributes().getAttribute(attributeName).toString();
            className.add(modalityStyle);
            Map<String, Double> modalityData;
            modalityData = iterationVsModalityClassCount.get(event.getIteration());
            if (modalityData == null) {
                modalityData = new HashMap<>();
                modalityData.put(modalityStyle, 1.0);
                iterationVsModalityClassCount.put(event.getIteration(), modalityData);
            } else {
                Double modalityStyleClassCount = modalityData.get(modalityStyle);
                if (modalityStyleClassCount == null)
                    modalityStyleClassCount = 0.0;
                modalityStyleClassCount = modalityStyleClassCount + 1;
                modalityData.put(modalityStyle, modalityStyleClassCount);
                iterationVsModalityClassCount.put(event.getIteration(), modalityData);
            }
        }
    }

    private double[][] buildModalityStyleDataSet() {
        List<Integer> iterationCount = GraphsStatsAgentSimEventsListener.getSortedIntegerList(iterationVsModalityClassCount.keySet());
        List<String> classList = GraphsStatsAgentSimEventsListener.getSortedStringList(className);
        if (iterationCount.size() == 0 || classList.size() == 0) {
            return null;
        }
        double[][] dataSet = new double[classList.size()][iterationCount.size()];
        for (int i = 0; i < classList.size(); i++) {
            double data[] = new double[iterationCount.size()];
            String className = classList.get(i);
            for (int j = 0; j < iterationCount.size(); j++) {
                Map<String, Double> modalityData = iterationVsModalityClassCount.get(j);
                Double classCount = modalityData.get(className);
                if (classCount == null) {
                    data[j] = 0;
                } else {
                    data[j] = classCount;
                }
            }
            dataSet[i] = data;
        }
        return dataSet;
    }

    private CategoryDataset buildModalityStyleGraphDataSet() {
        double dataSet[][] = buildModalityStyleDataSet();
        if (dataSet == null) {
            return null;
        }
        return DatasetUtilities.createCategoryDataset("", "", dataSet);
    }

    private void buildGraphFromPopulationProcessDataSet() throws IOException {
        CategoryDataset categoryDataset = buildModalityStyleGraphDataSet();
        if (categoryDataset == null) {
            return;
        }
        List<String> classList = GraphsStatsAgentSimEventsListener.getSortedStringList(className);
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(categoryDataset, graphTile, xAxisTitle, yAxisTitle, fileName, true);
        CategoryPlot plot = chart.getCategoryPlot();
        GraphUtils.plotLegendItems(plot, classList, categoryDataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename(fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }


}
