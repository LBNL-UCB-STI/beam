package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.*;

public class ModeChosenStats implements IGraphStats{
    private static Set<String> modesChosen = new TreeSet<>();
    private static Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private static final String graphTitle = "Mode Choice Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# mode chosen";
    private static final String fileName = "mode_choice.png";

    @Override
    public void processStats(Event event) {
        processModeChoice(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        if(modesFrequencyDataset!=null)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {

    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        modesChosen.clear();
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
        return GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFrequency.keySet());
    }

    private void processModeChoice(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        String mode = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_MODE);
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

        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFrequency.keySet());
        List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesChosen);
        if(0 == hoursList.size())
            return null;
        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[modesChosen.size()][maxHour + 1];
        for (int i = 0; i < modesChosenList.size(); i++) {
            String modeChosen = modesChosenList.get(i);
            dataset[i] = getHoursDataPerOccurrenceAgainstMode(modeChosen,maxHour);
        }
        return dataset;
    }
    private CategoryDataset buildModesFrequencyDatasetForGraph(){
        CategoryDataset categoryDataset = null;
        double [][] dataset= buildModesFrequencyDataset();
        if(dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
        return categoryDataset;
    }
    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset,graphTitle,xAxisTitle,yAxisTitle,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>();
        modesChosenList.addAll(modesChosen);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot,modesChosenList,dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

}
