package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.ReplanningEvent;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.MetricsSupport;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import static beam.sim.metrics.Metrics.ShortLevel;

public class RealizedModeAnalysis implements GraphAnalysis, MetricsSupport {


    private static final String graphTitle = "Realized Mode Histogram";
    private static final String referenceGraphTitle = "Reference Realized Mode Histogram";
    private static final String replanningGraphTitle = "Replanning Event Count";
    private static final String rootReplanningGraphTitle = "ReplanningEvent Count";
    private static final String yAxisTitleForReplanning = "count";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# mode chosen";
    static final String fileName = "realizedMode";
    private Map<Integer, Map<String, Double>> hourModeFrequency = new HashMap<>();
    private Map<String, Stack<ModeHour>> hourPerson = new HashMap<>();
    private Map<Integer, Map<String, Double>> realizedModeChoiceInIteration = new HashMap<>();
    private Map<Integer, Integer> affectedModeCount = new HashMap<>();
    private Map<Integer, Integer> rootAffectedModeCount = new HashMap<>();
    private Set<String> iterationTypeSet = new HashSet<>();
    private Set<String> cumulativeMode = new TreeSet<>();
    private Map<String, Map<Integer, Map<String, Integer>>> personHourModeCount = new HashMap<>();
    private Map<String , Double> benchMarkData;
    private Set<String> cumulativeReferenceMode = new TreeSet<>();

    //This map will always hold value as 0 or 1
    private Map<String, Integer> personIdList = new HashMap<>();

    private Logger log = LoggerFactory.getLogger(this.getClass());
    private final boolean writeGraph;
    private final StatsComputation<Tuple<Map<Integer, Map<String, Double>>, Set<String>>, double[][]> statComputation;

    public RealizedModeAnalysis(StatsComputation<Tuple<Map<Integer, Map<String, Double>>, Set<String>>, double[][]> statComputation, boolean writeGraph, BeamConfig beamConfig) {
        String benchMarkFileLocation = beamConfig.beam().calibration().mode().benchmarkFilePath();
        this.statComputation = statComputation;
        this.writeGraph = writeGraph;
        benchMarkData = benchMarkCSVLoader(benchMarkFileLocation);
    }

    public static class RealizedModesStatsComputation implements StatsComputation<Tuple<Map<Integer, Map<String, Double>>, Set<String>>, double[][]> {

        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Double>>, Set<String>> stat) {
            List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(stat.getFirst().keySet());
            List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.getSecond());
            if (0 == hoursList.size())
                return null;
            int maxHour = hoursList.get(hoursList.size() - 1);
            double[][] dataset = new double[stat.getSecond().size()][maxHour + 1];
            for (int i = 0; i < modesChosenList.size(); i++) {
                String modeChosen = modesChosenList.get(i);
                dataset[i] = getHoursDataPerOccurrenceAgainstMode(modeChosen, maxHour, stat.getFirst());
            }
            return dataset;
        }

        private double[] getHoursDataPerOccurrenceAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Double>> stat) {
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            for (int hour = 0; hour <= maxHour; hour++) {
                Map<String, Double> hourData = stat.get(hour);
                if (hourData != null) {
                    modeOccurrencePerHour[hour] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
                } else {
                    modeOccurrencePerHour[hour] = 0;
                }
            }
            return modeOccurrencePerHour;
        }
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof ReplanningEvent || event.getEventType().equalsIgnoreCase(ReplanningEvent.EVENT_TYPE) ||
                event instanceof ModeChoiceEvent || event.getEventType().equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE))
            processRealizedMode(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {

        Map<String, String> tags = new HashMap<>();
        tags.put("stats-type", "aggregated-mode-choice");

        updatePersonCount();

        hourModeFrequency.values().stream().filter(Objects::nonNull).flatMap(x -> x.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a + b))
                .forEach((mode, count) -> countOccurrenceJava(mode, count.longValue(), ShortLevel(), tags));

        updateRealizedModeChoiceInIteration(event.getIteration());
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        if (modesFrequencyDataset != null && writeGraph)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();

        String fileName;
        CategoryDataset dataset = buildRealizedModeChoiceDatasetForGraph();
        if (dataset != null && writeGraph) {
            fileName = outputDirectoryHierarchy.getOutputFilename("realizedModeChoice.png");
            createRootRealizedModeChoosenGraph(dataset, graphTitle, "# mode choosen" , fileName, cumulativeMode );
        }

        CategoryDataset referenceDataset = buildRealizedModeChoiceReferenceDatasetForGraph();
        if(referenceDataset != null && writeGraph){
            fileName = outputDirectoryHierarchy.getOutputFilename("referenceRealizedModeChoice.png");
            cumulativeReferenceMode.addAll(benchMarkData.keySet());
            createRootRealizedModeChoosenGraph(referenceDataset,referenceGraphTitle, "# mode choosen(Percent)" , fileName , cumulativeReferenceMode);
        }

        writeToRootCSV();
        writeToCSV(event);
        writeToReferenceCSV();

        DefaultCategoryDataset replanningModeCountDataset = replanningCountModeChoiceDataset();
        createReplanningCountModeChoiceGraph(replanningModeCountDataset, event.getIteration());
        writeToReplanningCSV(event);

        rootAffectedModeCount.put(event.getIteration(), affectedModeCount.values().stream().reduce(Integer::sum).orElse(0));
        fileName = outputDirectoryHierarchy.getOutputFilename("replanningCountModeChoice.png");
        writeToRootReplanningCountModeChoice(fileName);

    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        personIdList.clear();
        hourPerson.clear();
        personIdList.clear();
        personHourModeCount.clear();
        affectedModeCount.clear();
    }

    private void writeToRootReplanningCountModeChoice(String fileName) throws IOException{
        CategoryDataset dataset = rootReplanningCountModeChoiceDataset();
        createRootReplaningModeChoiceCountGraph(dataset, fileName);

    }

    // The modeChoice events for same person as of replanning event will be excluded in the form of CRC, CRCRC, CRCRCRC so on.
    private void processRealizedMode(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        Map<String, Double> hourData = hourModeFrequency.get(hour);
        if (event instanceof ModeChoiceEvent) {
            ModeChoiceEvent mce = (ModeChoiceEvent) event;
            String mode = mce.mode;
            String personId = mce.personId.toString();
            Map<String, String> tags = new HashMap<>();
            tags.put("stats-type", "mode-choice");
            tags.put("hour", "" + (hour + 1));

            countOccurrenceJava(mode, 1, ShortLevel(), tags);

            if (personIdList.containsKey(personId) && personIdList.get(personId) == 1) {
                personIdList.merge(personId, -1, Integer::sum);
                setHourPersonMode(hour, personId, mode, true);
                return;
            }

            if (hourData == null) {
                hourData = new HashMap<>();
            }

            hourData.merge(mode, 1.0, Double::sum);
            hourModeFrequency.put(hour, hourData);

            if (personIdList.remove(personId) != null) {
                updateHourMode(personId);
                personHourModeCount.remove(personId);
            }

            ModeHour modeHour = new ModeHour(mode, hour);
            Stack<ModeHour> modeHours = hourPerson.get(personId);
            if (modeHours == null) {
                modeHours = new Stack<>();
            }
            modeHours.push(modeHour);
            hourPerson.put(personId, modeHours);
            setHourPersonMode(hour, personId, mode, false);
        }

        if (event instanceof ReplanningEvent) {
            ReplanningEvent re = (ReplanningEvent) event;
            String person = re.getPersonId().toString();

            Stack<ModeHour> modeHours = hourPerson.get(person);
            affectedModeCount.merge(hour, 1, Integer::sum);


            if (personIdList.containsKey(person) && personIdList.get(person) == 0) {
                personIdList.merge(person, 1, Integer::sum);
                return;
            }

            if (modeHours != null && modeHours.size() > 0
                    && !personIdList.containsKey(person)) {

                personIdList.merge(person, 1, Integer::sum);

                ModeHour modeHour = modeHours.pop();
                hourPerson.put(person, modeHours);

                hourData = hourModeFrequency.get(modeHour.getHour());
                hourData.merge(modeHour.getMode(), -1.0, Double::sum);
                hourModeFrequency.put(hour, hourData);
            }
        }
    }

    // adding proportionate of replanning to mode choice
    public void updateHourMode(String personId) {
        Map<Integer, Map<String, Integer>> hourModeCount = personHourModeCount.get(personId);
        if (hourModeCount != null) {
            double sum = hourModeCount.values().stream().map(Map::values).mapToInt(i -> i.stream().mapToInt(Integer::intValue).sum()).sum();
            Set<Integer> hours = hourModeCount.keySet();

            for (Integer h : hours) {
                Map<String, Integer> modeCounts = hourModeCount.get(h);
                if (sum >= 2) {
                    modeCounts.forEach((k, v) -> {        //k is mode, v is modecount
                        Map<String, Double> oldHourData = hourModeFrequency.get(h);
                        if(oldHourData != null){
                            oldHourData.merge(k, (double) v / sum, Double::sum);
                            hourModeFrequency.put(h, oldHourData);
                        }
                    });
                }
            }
        }
    }

    public void setHourPersonMode(int hour, String personId, String mode, boolean isUpdateExisting) {
        Map<Integer, Map<String, Integer>> hourModeCount = personHourModeCount.get(personId);
        if (hourModeCount == null) {
            hourModeCount = new HashMap<>();
        }
        Map<String, Integer> modeCnt = hourModeCount.get(hour);
        if (modeCnt == null) {
            modeCnt = new HashMap<>();
        }
        if (isUpdateExisting)
            modeCnt.put(mode, 1);
        else {
            hourModeCount.clear();
            modeCnt.put(mode, 1);
        }
        hourModeCount.put(hour, modeCnt);
        personHourModeCount.put(personId, hourModeCount);
    }

    public void updatePersonCount() {
        personHourModeCount.keySet().forEach(person -> updateHourMode(person));
    }

    //    accumulating data for each iteration
    public void updateRealizedModeChoiceInIteration(Integer iteration) {
        Set<Integer> hours = hourModeFrequency.keySet();
        Map<String, Double> totalModeChoice = new HashMap<>();
        for (Integer hour : hours) {
            Map<String, Double> iterationHourData = hourModeFrequency.get(hour);
            if (iterationHourData != null) {
                Set<String> iterationModes = iterationHourData.keySet();
                for (String iterationMode : iterationModes) {
                    Double freq = iterationHourData.get(iterationMode);
                    totalModeChoice.merge(iterationMode, freq, (a, b) -> b + a);
                }
            }
        }
        iterationTypeSet.add("it." + iteration);
        realizedModeChoiceInIteration.put(iteration, totalModeChoice);

    }

    private CategoryDataset buildModesFrequencyDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildModesFrequencyDataset();
        if (dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Mode ", "", dataset);

        return categoryDataset;
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, true);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(getModesChosen());
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    //This is used for removing columns if all entries is 0
    private Set<String> getModesChosen() {

        Set<String> modes = new TreeSet<>();
        Map<String, Double> modeCountBucket = new HashMap<>();
        hourModeFrequency.keySet().stream().filter(hour -> hourModeFrequency.get(hour) != null).forEach(hour -> hourModeFrequency.get(hour).keySet().
                forEach(mode -> {
                    Double count = modeCountBucket.get(mode);
                    Map<String, Double> modeFrequency = hourModeFrequency.get(hour);
                    if (modeFrequency != null) {
                        if (count != null) {
                            modeCountBucket.put(mode, count + modeFrequency.get(mode));
                        } else {
                            modeCountBucket.put(mode, modeFrequency.get(mode));
                        }
                    }
                }));

        modeCountBucket.keySet().forEach(mode -> {
            if (modeCountBucket.get(mode) != null && modeCountBucket.get(mode) != 0) {
                modes.add(mode);
            }
        });
        cumulativeMode.addAll(modes);
        cumulativeReferenceMode.addAll(modes);
        return modes;
    }


    // dataset for root graph
    private CategoryDataset buildRealizedModeChoiceDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildTotalRealizedModeChoiceDataset();

        if (dataset != null) {
            categoryDataset = createCategoryDataset("it.", dataset);
        }
        return categoryDataset;
    }

    public CategoryDataset createCategoryDataset(String columnKeyPrefix, double[][] data) {

        DefaultCategoryDataset result = new DefaultCategoryDataset();
        for (int r = 0; r < data.length; r++) {
            String rowKey = String.valueOf(r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue(data[r][c], rowKey, columnKey);
            }
        }
        return result;
    }

    private double[][] buildTotalRealizedModeChoiceDataset() {
        return statComputation.compute(new Tuple<>(realizedModeChoiceInIteration, cumulativeMode));
    }


    //reference realized mode detaset
    private CategoryDataset buildRealizedModeChoiceReferenceDatasetForGraph() throws IOException {
        CategoryDataset categoryDataset = null;
        double[][] dataset = statComputation.compute(new Tuple<>(realizedModeChoiceInIteration, cumulativeReferenceMode));

        if (dataset != null) {
            categoryDataset = createReferenceCategoryDataset("it.", dataset);
        }
        return categoryDataset;
    }

    // The data is converted into average and compared with the data of benchmark.
    private CategoryDataset createReferenceCategoryDataset(String columnKeyPrefix, double[][] data) {
        DefaultCategoryDataset result = new DefaultCategoryDataset();
        List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(benchMarkData.keySet());
        double sum = benchMarkData.values().stream().reduce((x, y) -> x + y).orElse(0.0);
        for (int i = 0; i < modesChosenList.size(); i++) {
            String rowKey = String.valueOf(i + 1);
            result.addValue((benchMarkData.get(modesChosenList.get(i)) * 100) / sum, rowKey, "benchmark");
        }
        int max = 0;
        for (double[] aData : data) {
            if (aData.length > max) {
                max = aData.length;
            }
        }
        double[] sumOfColumns = new double[max];
        for (double[] aData : data) {
            for (int c = 0; c < aData.length; c++) {
                sumOfColumns[c] += aData[c];
            }
        }

        for (int r = 0; r < data.length; r++) {
            String rowKey = String.valueOf(r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue((data[r][c] * 100) / sumOfColumns[c], rowKey, columnKey);
            }
        }
        return result;
    }

    // generating realized mode and reference realized mode graphs in root directory
    private void createRootRealizedModeChoosenGraph(CategoryDataset dataset, String graphTitle , String yAxisTitle,  String fileName, Set<String> modeSet) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, "Iteration", yAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(modeSet);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    // generating graph in root directory for replanningCountModeChoice
    private void createRootReplaningModeChoiceCountGraph(CategoryDataset dataset, String fileName) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, rootReplanningGraphTitle, "Iteration", "Number of events", fileName, false);
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    double[][] buildModesFrequencyDataset() {

        Set<String> modeChoosen = getModesChosen();
        return statComputation.compute(new Tuple<>(hourModeFrequency, modeChoosen));
    }

    private void createReplanningCountModeChoiceGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, replanningGraphTitle, xAxisTitle, yAxisTitleForReplanning, "replanningCountModeChoice", false);
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "replanningCountModeChoice" + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    public DefaultCategoryDataset replanningCountModeChoiceDataset() {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        int max = hourModeFrequency.keySet().stream().mapToInt(x -> x).max().orElse(0);
        for (int hour = 0; hour <= max; hour++) {
            dataset.addValue((Number) affectedModeCount.get(hour), 0, hour);
        }
        return dataset;
    }


    public DefaultCategoryDataset rootReplanningCountModeChoiceDataset() {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        rootAffectedModeCount.forEach((k,v) -> dataset.addValue((Number) v , 0, k));
        return dataset;
    }

    private void writeToCSV(IterationEndsEvent event) {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(event.getIteration(), fileName + ".csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = getModesChosen();

            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("hours," + heading);
            out.newLine();

            int max = hourModeFrequency.keySet().stream().mapToInt(x -> x).max().orElse(0);

            for (int hour = 0; hour <= max; hour++) {
                Map<String, Double> modeCount = hourModeFrequency.get(hour);
                StringBuilder builder = new StringBuilder(hour + 1 + "");
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            builder.append(",").append(modeCount.get(mode));
                        } else {
                            builder.append(",0");
                        }
                    }
                } else {
                    for (String ignored : modes) {
                        builder.append(",0");
                    }
                }
                out.write(builder.toString());
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("CSV generation failed.", e);
        }
    }

    // csv for root graph
    public void writeToRootCSV() {
        String fileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("realizedModeChoice.csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(fileName)))) {
            Set<String> modes = cumulativeMode;
            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            int max = realizedModeChoiceInIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Double> modeCountIteration = realizedModeChoiceInIteration.get(iteration);
                StringBuilder stringBuilder = new StringBuilder(iteration + "");
                if (modeCountIteration != null) {
                    for (String mode : modes) {
                        if (modeCountIteration.get(mode) != null) {
                            stringBuilder.append(",").append(modeCountIteration.get(mode));
                        } else {
                            stringBuilder.append(",0");
                        }
                    }
                } else {
                    for (String ignored : modes) {
                        stringBuilder.append(",0");
                    }
                }
                out.write(stringBuilder.toString());
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("error in generating CSV", e);
        }
    }


    public void writeToReferenceCSV(){
        String fileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("referenceRealizedModeChoice.csv");
        try(BufferedWriter out = new BufferedWriter(new FileWriter(new File(fileName)))){
            Set<String> modes = cumulativeReferenceMode;
            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations,"+heading);
            out.newLine();

            StringBuilder builder = new StringBuilder("benchmark");
            double sum = benchMarkData.values().stream().reduce((x, y) -> x + y).orElse(0.0);
            for(String d:cumulativeReferenceMode){
                if(benchMarkData.get(d) == null){
                    builder.append(",0.0");
                }
                builder.append("," + benchMarkData.get(d)*100/sum);
            }
            out.write(builder.toString());
            out.newLine();


            int max = realizedModeChoiceInIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            double[] sumInIteration = new double[max + 1];
            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Double> modeCount = realizedModeChoiceInIteration.get(iteration);
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            sumInIteration[iteration] += modeCount.get(mode);
                        }
                    }
                }
            }

            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Double> modeCount = realizedModeChoiceInIteration.get(iteration);
                builder = new StringBuilder(iteration + "");
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            builder.append(",").append((modeCount.get(mode) * 100) / sumInIteration[iteration]);
                        } else {
                            builder.append(",0");
                        }
                    }
                } else {
                    for (String ignored : modes) {
                        builder.append(",0");
                    }
                }
                out.write(builder.toString());
                out.newLine();
            }

        }catch (IOException ex){
            log.error("error in generating csv " , ex);

        }
    }

    private void writeToReplanningCSV(IterationEndsEvent event) {
        String fileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(event.getIteration(), "replanningCountModeChoice.csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(fileName)))) {
            String heading = "hour,count";
            out.write(heading);
            out.newLine();
            int max = hourModeFrequency.keySet().stream().mapToInt(x -> x).max().orElse(0);
            for (int hour = 0; hour <= max; hour++) {
                String line;
                if (affectedModeCount.get(hour) != null) {
                    line = hour + "," + affectedModeCount.get(hour);
                } else {
                    line = hour + "," + "0";
                }
                out.write(line);
                out.newLine();
            }
        } catch (IOException ex) {
            log.error("exception occurred due to ", ex);
        }
    }

    public Map<Integer, Integer> getAffectedModeCount() {
        return affectedModeCount;
    }

    private Map<String, Double> benchMarkCSVLoader(String path) {

        Map<String, Double> benchMarkData = new HashMap<>();

        try (FileReader fileReader = new FileReader(path)) {
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            String mode[] = bufferedReader.readLine().split(",");
            String modeData[] = bufferedReader.readLine().split(",");

            for (int i = 1; i < mode.length; i++) {
                benchMarkData.put(mode[i], Double.parseDouble(modeData[i]));
            }
        } catch (Exception ex) {
            log.warn("Unable to load benchmark CSV via path '{}'", path);
        }
        return benchMarkData;
    }

    public class ModeHour {
        private String mode;
        private Integer hour;

        public ModeHour(String mode, Integer hour) {
            this.mode = mode;
            this.hour = hour;
        }

        public String getMode() {
            return mode;
        }

        public Integer getHour() {
            return hour;
        }

        @Override
        public String toString() {
            return "[Mode: " + mode + ", Hour: " + hour + "]";
        }

        @Override
        public boolean equals(Object o) {

            if (o == this) return true;
            if (!(o instanceof ModeHour)) {
                return false;
            }

            ModeHour modeHour = (ModeHour) o;

            return modeHour.mode.equals(mode) &&
                    modeHour.hour.equals(hour);
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + mode.hashCode();
            result = 31 * result + hour.hashCode();
            return result;
        }
    }
}

