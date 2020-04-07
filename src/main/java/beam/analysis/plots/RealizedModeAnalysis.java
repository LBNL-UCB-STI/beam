package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.ReplanningEvent;
import beam.sim.config.BeamConfig;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.ListUtils;
import org.jfree.chart.JFreeChart;
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


public class RealizedModeAnalysis extends BaseModeAnalysis {

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
    private Map<String, Double> benchMarkData;
    private Set<String> cumulativeReferenceMode = new TreeSet<>();
    private Map<String,List<String>> personReplanningChain = new HashMap<>();
    private Map<String, Integer> replanningReasonCount = new HashMap<>();
    private Map<Integer, Map<String, Integer>> replanningReasonCountPerIter = new HashMap<>();
    //This map will always hold value as 0 or 1
    private Map<String, Integer> personIdList = new HashMap<>();

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
        if (modesFrequencyDataset != null && writeGraph) {
            String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(event.getIteration(), fileName + ".png");
            createGraphInRootDirectory(modesFrequencyDataset, graphTitle, graphImageFile, xAxisTitle, yAxisTitle, getModesChosen());
        }
        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();

        String fileName;
        CategoryDataset dataset = buildRealizedModeChoiceDatasetForGraph();
        if (dataset != null && writeGraph) {
            fileName = outputDirectoryHierarchy.getOutputFilename("realizedModeChoice.png");
            createGraphInRootDirectory(dataset, graphTitle, fileName, "Iteration", "# mode choosen", cumulativeMode);
        }

        CategoryDataset referenceDataset = buildRealizedModeChoiceReferenceDatasetForGraph();
        if (referenceDataset != null && writeGraph) {
            fileName = outputDirectoryHierarchy.getOutputFilename("referenceRealizedModeChoice.png");
            cumulativeReferenceMode.addAll(benchMarkData.keySet());
            createGraphInRootDirectory(referenceDataset, referenceGraphTitle, fileName, "Iteration", "# mode choosen(Percent)", cumulativeReferenceMode);
        }

        Map<String, Integer> modeCount = calculateModeCount();
        writeToReplaningChainCSV(event, modeCount);
        
        writeToRootCSV(GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("realizedModeChoice.csv"), realizedModeChoiceInIteration, cumulativeMode);
        writeToCSV(event);
        writeToReferenceCSV();

        DefaultCategoryDataset replanningModeCountDataset = replanningCountModeChoiceDataset();
        createReplanningCountModeChoiceGraph(replanningModeCountDataset, event.getIteration());
        writeToReplanningCSV(event);

        rootAffectedModeCount.put(event.getIteration(), affectedModeCount.values().stream().reduce(Integer::sum).orElse(0));
        fileName = outputDirectoryHierarchy.getOutputFilename("replanningCountModeChoice.png");
        writeToRootReplanningCountModeChoice(fileName);

        writeReplanningReasonCountCSV(event.getIteration());

        replanningReasonCountPerIter.put(event.getIteration(),replanningReasonCount.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        writeReplanningReasonCountRootCSV();
    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        personIdList.clear();
        hourPerson.clear();
        personHourModeCount.clear();
        affectedModeCount.clear();
        personReplanningChain.clear();
        replanningReasonCount.clear();
    }

    private void writeToRootReplanningCountModeChoice(String fileName) throws IOException {
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
            String personId = mce.getPersonId().toString();
            Map<String, String> tags = new HashMap<>();
            tags.put("stats-type", "mode-choice");
            tags.put("hour", "" + (hour + 1));

            countOccurrenceJava(mode, 1, ShortLevel(), tags);
            personReplanningChain.merge(personId , Lists.newArrayList(mode), ListUtils::union);
            if (personIdList.containsKey(personId) && personIdList.get(personId) == 1) {
                personIdList.put(personId, 0);
                setHourPersonMode(hour, personId, mode, true);
                return;
            }

            if (personIdList.remove(personId) != null) {
                updateHourMode(personId);
                personHourModeCount.remove(personId);
            }
            if (hourData == null) {
                hourData = new HashMap<>();
            }

            hourData.merge(mode, 1.0, Double::sum);
            hourModeFrequency.put(hour, hourData);
            ModeHour modeHour = new ModeHour(mode, hour);
            Stack<ModeHour> modeHours = hourPerson.getOrDefault(personId, new Stack<>());
            modeHours.push(modeHour);
            hourPerson.put(personId, modeHours);
            setHourPersonMode(hour, personId, mode, false);
        }

        if (event instanceof ReplanningEvent) {
            ReplanningEvent re = (ReplanningEvent) event;
            String person = re.getPersonId().toString();

            personReplanningChain.merge(person , Lists.newArrayList(re.getEventType()), ListUtils::union);
            Stack<ModeHour> modeHours = hourPerson.get(person);
            affectedModeCount.merge(hour, 1, Integer::sum);
            replanningReasonCount.merge(re.getReason(), 1, Integer::sum);

            if (personIdList.containsKey(person) && personIdList.get(person) == 0) {
                personIdList.put(person, 1);
                return;
            }

            if (modeHours != null && modeHours.size() > 0
                    && !personIdList.containsKey(person)) {

                personIdList.put(person, 1);

                ModeHour modeHour = modeHours.pop();
                hourPerson.put(person, modeHours);

                hourData = hourModeFrequency.get(modeHour.getHour());
                hourData.merge(modeHour.getMode(), -1.0, Double::sum);
                hourModeFrequency.put(modeHour.getHour(), hourData);
            }
        }
    }

    // adding proportionate of replanning to mode choice
    public void updateHourMode(String personId) {
        Map<Integer, Map<String, Integer>> hourModeCount = personHourModeCount.get(personId);
        if (hourModeCount != null) {
            double countSum = hourModeCount.values().stream().map(Map::values).mapToInt(i -> i.stream().mapToInt(Integer::intValue).sum()).sum();

            Set<String> modes = new HashSet<>();
            hourModeCount.values().stream().map(Map::keySet).forEach(modes::addAll);
            Set<Integer> hours = hourModeCount.keySet();
            double sum = modes.size();
            if (countSum >= 2 && sum == 1) {
                Optional<Integer> optionalHour = hours.stream().findFirst();
                if (optionalHour.isPresent()) {
                    int hour = optionalHour.get();
                    Map<String, Double> oldHourData = hourModeFrequency.getOrDefault(hour, new HashMap<>());
                    Optional<String> optionalMode = modes.stream().findFirst();
                    if (optionalMode.isPresent()) {
                        oldHourData.merge(optionalMode.get(), 1.0, Double::sum);
                        hourModeFrequency.put(hour, oldHourData);
                    }
                }

            } else if (countSum >= 2 && sum > 1) {
                for (String mode : modes) {
                    Integer hour = null;
                    for (Integer h : hours) {
                        Map<String, Integer> modeCount = hourModeCount.get(h);
                        if (modeCount != null) {
                            Integer tmpModeCount = modeCount.get(mode);
                            if (tmpModeCount != null) {
                                hour = h;
                            }
                        }
                    }
                    if (hour != null) {
                        Map<String, Double> oldHourData = hourModeFrequency.getOrDefault(hour, new HashMap<>());
                        oldHourData.merge(mode, 1.0 / sum, Double::sum);
                        hourModeFrequency.put(hour, oldHourData);
                    }
                }
            }
        }
    }

    public void setHourPersonMode(int hour, String personId, String mode, boolean isUpdateExisting) {
        Map<Integer, Map<String, Integer>> hourModeCount = personHourModeCount.getOrDefault(personId, new HashMap<>());
        Map<String, Integer> modeCnt = hourModeCount.getOrDefault(hour, new HashMap<>());
        if (isUpdateExisting) {
            modeCnt.merge(mode, 1, Integer::sum);
        } else {
            modeCnt.clear();
            hourModeCount.clear();
            modeCnt.put(mode, 1);
        }
        hourModeCount.put(hour, modeCnt);
        personHourModeCount.put(personId, hourModeCount);
    }

    public void updatePersonCount() {
        personHourModeCount.keySet().forEach(this::updateHourMode);
    }

    //    accumulating data for each iteration
    public void updateRealizedModeChoiceInIteration(Integer iteration) {
        Map<String, Double> totalModeChoice = new HashMap<>();
        hourModeFrequency.values().forEach(iterationHourData -> {
            if (iterationHourData != null) {
                iterationHourData.forEach((iterationMode, freq) -> {
                    totalModeChoice.merge(iterationMode, freq, Double::sum);
                });
            }
        });
        iterationTypeSet.add("it." + iteration);
        realizedModeChoiceInIteration.put(iteration, totalModeChoice);
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph() {
        double[][] dataset = buildModesFrequencyDataset();
        if (dataset != null) {
            return DatasetUtilities.createCategoryDataset("Mode ", "", dataset);
        }
        return null;
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
        cumulativeReferenceMode.addAll(benchMarkData.keySet());
        return modes;
    }


    // dataset for root graph
    private CategoryDataset buildRealizedModeChoiceDatasetForGraph() {
        double[][] dataset = buildTotalRealizedModeChoiceDataset();
        if (dataset != null) {
            return createCategoryDataset("it.", dataset);
        }
        return null;
    }

    private double[][] buildTotalRealizedModeChoiceDataset() {
        return statComputation.compute(new Tuple<>(realizedModeChoiceInIteration, cumulativeMode));
    }


    //reference realized mode detaset
    private CategoryDataset buildRealizedModeChoiceReferenceDatasetForGraph() throws IOException {
        CategoryDataset categoryDataset = null;
        double[][] dataset = statComputation.compute(new Tuple<>(realizedModeChoiceInIteration, cumulativeReferenceMode));

        if (dataset != null) {
            categoryDataset = createReferenceCategoryDataset("it.", dataset, benchMarkData);
        }
        return categoryDataset;
    }

    // generating graph in root directory for replanningCountModeChoice
    private void createRootReplaningModeChoiceCountGraph(CategoryDataset dataset, String fileName) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, rootReplanningGraphTitle, "Iteration", "Number of events", fileName, false);
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    double[][] buildModesFrequencyDataset() {
        return statComputation.compute(new Tuple<>(hourModeFrequency, getModesChosen()));
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
        rootAffectedModeCount.forEach((k, v) -> dataset.addValue((Number) v, 0, k));
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

    public void writeToReferenceCSV() {
        String fileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("referenceRealizedModeChoice.csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(fileName)))) {
            Set<String> modes = cumulativeReferenceMode;
            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            StringBuilder builder = new StringBuilder("benchmark");
            double sum = benchMarkData.values().stream().reduce((x, y) -> x + y).orElse(0.0);
            for (String d : cumulativeReferenceMode) {
                if (benchMarkData.get(d) == null) {
                    builder.append(",0.0");
                } else {
                    builder.append("," + benchMarkData.get(d) * 100 / sum);
                }
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

        } catch (IOException ex) {
            log.error("error in generating csv ", ex);

        }
    }

    public void writeReplanningReasonCountCSV(Integer iteration) {
        String fileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iteration,"replanningEventReason.csv");
        String header = "ReplanningReason,Count";
        try(FileWriter writer = new FileWriter(new File(fileName));
            BufferedWriter out = new BufferedWriter(writer)){
            out.write(header);
            out.newLine();
            for (Map.Entry<String,Integer> entry : replanningReasonCount.entrySet()){
                out.write(entry.getKey()+","+entry.getValue());
                out.newLine();
            }
        } catch (IOException ex) {
            log.error("error in generating csv ", ex);

        }
    }

    public void writeReplanningReasonCountRootCSV() {
        String fileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("replanningEventReason.csv");
        Set<String> headerItem = replanningReasonCountPerIter.values().stream().flatMap(header -> header.keySet().stream()).collect(Collectors.toSet());
        String csvHeader = "Mode,"+String.join(",", headerItem);

        try(FileWriter writer = new FileWriter(new File(fileName));
            BufferedWriter out = new BufferedWriter(writer)){
            out.write(csvHeader);
            out.newLine();
            for (Map.Entry<Integer, Map<String,Integer>> entry : replanningReasonCountPerIter.entrySet()){
                Map<String, Integer> replanningReasonCount = entry.getValue();
                String row = entry.getKey()+","+headerItem.stream().map(item -> replanningReasonCount.getOrDefault(item, 0).toString()).collect(Collectors.joining(","));
                out.write(row);
                out.newLine();
            }
        } catch (IOException ex) {
            log.error("error in generating csv ", ex);
        }
    }

    public Map<String, Integer> calculateModeCount(){

        String REPLANNING_SEPARATOR = "-"+ReplanningEvent.EVENT_TYPE+"-";
        Set<String> persons = personReplanningChain.keySet();
        //This is holding modes-replanning-modes as key and there count as value
        Map<String, Integer> modeCount = new HashMap<>();
        for(String person: persons){
            List<String> modes = personReplanningChain.get(person);
            if(modes.size() > 1){
                StringBuffer lastModes = new StringBuffer();
                for(String mode: modes){
                    if(ReplanningEvent.EVENT_TYPE.equals(mode)){
                        lastModes.append(REPLANNING_SEPARATOR);
                    }
                    else if(lastModes.toString().endsWith(REPLANNING_SEPARATOR)){
                        //This is used to decrease previous key count(if any)
                        String lastModeCount = lastModes.substring(0, lastModes.length()- REPLANNING_SEPARATOR.length());
                        if(modeCount.containsKey(lastModeCount)){
                            modeCount.merge(lastModeCount, -1, Integer::sum);
                        }
                        lastModes.append(mode);
                        modeCount.merge(lastModes.toString(), 1, Integer::sum);
                    }else{
                        lastModes = new StringBuffer(mode);
                    }
                }
            }
        }
        return modeCount;
    }

    private void writeToReplaningChainCSV(IterationEndsEvent event, Map<String, Integer> modeCount) {
        String fileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(event.getIteration(), "replanningEventChain.csv");

        try(FileWriter fileWriter = new FileWriter(new File(fileName))){
            try (BufferedWriter out = new BufferedWriter(fileWriter)) {
                String heading = "modeChoiceReplanningEventChain,count";
                out.write(heading);
                out.newLine();
                Set<String> modes = modeCount.keySet();
                for(String mode: modes){
                    int count = modeCount.get(mode);
                    if(count > 0){
                        out.write(mode+","+count);
                        out.newLine();
                    }
                }
            } catch (IOException ex) {
                log.error("exception occurred due to ", ex);
            }
        }
        catch (IOException exception){
            log.error("exception occurred due to ", exception);
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

