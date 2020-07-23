package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.analysis.plots.filterevent.AllEventsFilter$;
import beam.analysis.plots.filterevent.FilterEvent;
import beam.analysis.via.CSVWriter;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.SimulationMetricCollector;
import org.jfree.data.category.CategoryDataset;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static beam.sim.metrics.Metrics.ShortLevel;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

public class ModeChosenAnalysis extends BaseModeAnalysis {

    private static final String graphTitle = "Mode Choice Histogram";
    private static final String graphTitleBenchmark = "Reference Mode Choice Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# mode chosen";
    static final String modeChoiceFileBaseName = "modeChoice";
    static final String referenceModeChoiceFileBaseName = "referenceModeChoice";

    private final Set<String> iterationTypeSet = new HashSet<>();
    private final Map<Integer, Map<String, Integer>> modeChoiceInIteration = new HashMap<>();

    private final Set<String> modesChosen = new TreeSet<>();
    private final Set<String> cumulativeModeChosenForModeChoice = new TreeSet<>();
    private final Set<String> cumulativeModeChosenForReference = new TreeSet<>();
    private final Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private final Map<String, Double> benchMarkData;
    private final Map<ModeChosenAvailableAlternatives, Integer> modeChosenAvailableAlternativesCount = new HashMap<>();
    private final boolean writeGraph;

    private final StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation;
    private final SimulationMetricCollector simMetricCollector;
    private final FilterEvent filterEvent;
    private final OutputDirectoryHierarchy ioController;

    public ModeChosenAnalysis(SimulationMetricCollector simMetricCollector, StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation, BeamConfig beamConfig, FilterEvent filterEvent, OutputDirectoryHierarchy ioController) {
        final String benchmarkFileLoc = beamConfig.beam().calibration().mode().benchmarkFilePath();
        this.simMetricCollector = simMetricCollector;
        this.statComputation = statComputation;
        this.filterEvent = filterEvent;
        benchMarkData = benchmarkCsvLoader(benchmarkFileLoc);
        writeGraph = beamConfig.beam().outputs().writeGraphs();
        this.ioController = ioController;
    }

    public ModeChosenAnalysis(SimulationMetricCollector simMetricCollector, StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation, BeamConfig beamConfig, OutputDirectoryHierarchy ioController) {
        this(simMetricCollector, statComputation, beamConfig, AllEventsFilter$.MODULE$, ioController);
    }

    private OutputDirectoryHierarchy controllerIo() {
        return ioController;
    }

    private String iterationFilename(final int iterationNumber, final String fileName, final String suffix) {
        final String theFinalName = fileName + filterEvent.graphNamePreSuffix() + suffix;
        return controllerIo().getIterationFilename(iterationNumber, theFinalName);
    }

    private String outputFilename(final String filename, final String suffix) {
        final String namePreSuffix = filterEvent.graphNamePreSuffix();
        String filenameResult = filename + namePreSuffix + suffix;
        return controllerIo().getOutputFilename(filenameResult);
    }

    public static String getModeChoiceFileBaseName() {
        return modeChoiceFileBaseName;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof ModeChoiceEvent && filterEvent.shouldProcessEvent(event)) {
            processModeChoice((ModeChoiceEvent) event);
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        Map<String, String> tags = new HashMap<>();
        tags.put("stats-type", "aggregated-mode-choice");
        hourModeFrequency.values().stream().flatMap(x -> x.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum))
                .forEach((mode, count) -> countOccurrenceJava(mode, count, ShortLevel(), tags));
        updateModeChoiceInIteration(event.getIteration());
        writeModeChosen(event);
        writeCumulativeModeChosen();
        writeReferenceModeChoice();
        writeModeChosenAvailableAlternativeCSV(event.getIteration());
    }

    private void writeModeChosen(IterationEndsEvent event) throws IOException {
        if (writeGraph) {
            CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
            if (modesFrequencyDataset != null) {
                String graphImageFile = iterationFilename(event.getIteration(), modeChoiceFileBaseName, ".png");
                createGraphInRootDirectory(modesFrequencyDataset, graphTitle, graphImageFile, xAxisTitle, yAxisTitle, modesChosen);
            }
        }
        createModeChosenCSV(hourModeFrequency, event.getIteration(), modeChoiceFileBaseName);
    }

    private void writeCumulativeModeChosen() throws IOException {
        String fileName = outputFilename(modeChoiceFileBaseName, ".png");
        CategoryDataset dataset = buildModeChoiceDatasetForGraph();
        if (dataset != null && writeGraph) {
            createGraphInRootDirectory(dataset, graphTitle, fileName, "Iteration", "# mode choosen", cumulativeModeChosenForModeChoice);
        }
        writeToRootCSV(outputFilename(modeChoiceFileBaseName, ".csv"), modeChoiceInIteration, cumulativeModeChosenForModeChoice);
    }

    private void writeReferenceModeChoice() throws IOException {
        final String fileName = outputFilename(referenceModeChoiceFileBaseName, ".png");
        cumulativeModeChosenForReference.addAll(benchMarkData.keySet());
        CategoryDataset referenceDataset = buildModeChoiceReferenceDatasetForGraph();
        if (referenceDataset != null && writeGraph) {
            createGraphInRootDirectory(referenceDataset, graphTitleBenchmark, fileName, "Iteration", "# mode choosen(Percent)", cumulativeModeChosenForReference);
        }
        writeToRootCSVForReference(referenceModeChoiceFileBaseName);
    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        modesChosen.clear();
        modeChosenAvailableAlternativesCount.clear();
    }

    private void processModeChoice(ModeChoiceEvent event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        String mode = event.mode;

        HashMap<String, String> tags = new HashMap<>();
        tags.put("mode", mode);
        int time = (int) event.getTime();
        simMetricCollector.writeIterationJava("mode-choices", time, 1, tags, false);

        modesChosen.add(mode);
        cumulativeModeChosenForModeChoice.add(mode);
        cumulativeModeChosenForReference.add(mode);
        Map<String, Integer> hourData = hourModeFrequency.get(hour);
        Integer frequency = 1;
        if (hourData != null) {
            frequency = hourData.getOrDefault(mode, 0);
            frequency++;
        } else {
            hourData = new HashMap<>();
        }
        hourData.put(mode, frequency);
        hourModeFrequency.put(hour, hourData);

        modeChosenAvailableAlternativesCount.merge(new ModeChosenAvailableAlternatives(mode, event.availableAlternatives), 1, Integer::sum);
    }

    //    accumulating data for each iteration
    private void updateModeChoiceInIteration(Integer iteration) {
        Set<Integer> hours = hourModeFrequency.keySet();
        Map<String, Integer> totalModeChoice = new HashMap<>();
        for (Integer hour : hours) {
            Map<String, Integer> iterationHourData = hourModeFrequency.get(hour);
            Set<String> iterationModes = iterationHourData.keySet();
            for (String iterationMode : iterationModes) {
                Integer freq = iterationHourData.get(iterationMode);
                Integer iterationFrequency = defaultIfNull(totalModeChoice.get(iterationMode), 0);
                totalModeChoice.put(iterationMode, freq + iterationFrequency);
            }
        }
        iterationTypeSet.add("it." + iteration);
        modeChoiceInIteration.put(iteration, totalModeChoice);
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph() {
        double[][] dataset = compute();
        return dataset == null
                ? null
                : GraphUtils.createCategoryDataset("Mode ", "", dataset);
    }

    double[][] compute() {
        return statComputation.compute(new Tuple<>(hourModeFrequency, modesChosen));
    }

    private void createModeChosenCSV(Map<Integer, Map<String, Integer>> hourModeChosen, int iterationNumber, String fileBaseName) {
        if (hourModeChosen.isEmpty()) {
            return;
        }
        final String separator = ",";

        CSVWriter csvWriter = new CSVWriter(iterationFilename(iterationNumber, fileBaseName, ".csv"));
        BufferedWriter bufferedWriter = csvWriter.getBufferedWriter();

        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeChosen.keySet());
        List<String> modesFuelList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesChosen);

        int maxHour = hours.get(hours.size() - 1);
        try {
            bufferedWriter.append("Modes");
            bufferedWriter.append(separator);
            for (int j = 0; j <= maxHour; j++) {
                bufferedWriter.append("Bin_")
                        .append(String.valueOf(j))
                        .append(separator);
            }
            bufferedWriter.append("\n");

            for (String modeChosen : modesFuelList) {

                bufferedWriter.append(modeChosen).append(separator);

                for (int j = 0; j <= maxHour; j++) {
                    Map<String, Integer> modesData = hourModeChosen.get(j);

                    String modeHourValue = "0";

                    if (modesData != null && modesData.get(modeChosen) != null) {
                        modeHourValue = modesData.get(modeChosen).toString();
                    }

                    bufferedWriter.append(modeHourValue).append(separator);
                }
                bufferedWriter.append("\n");
            }
            bufferedWriter.flush();
            csvWriter.closeFile();

        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    //    dataset for root graph
    private CategoryDataset buildModeChoiceDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = statComputation.compute(new Tuple<>(modeChoiceInIteration, cumulativeModeChosenForModeChoice));
        if (dataset != null) {
            categoryDataset = GraphUtils.createCategoryDataset("", "it.", dataset);
        }
        return categoryDataset;
    }

    //    dataset for root graph
    private CategoryDataset buildModeChoiceReferenceDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = statComputation.compute(new Tuple<>(modeChoiceInIteration, cumulativeModeChosenForReference));

        if (dataset != null) {
            categoryDataset = createReferenceCategoryDataset("it.", dataset, benchMarkData);
        }
        return categoryDataset;
    }

    //TODO used only in GraphsStatsAgentSimEventsListener, which should probably be refactored anyway
    public void writeToRootCSV(String fileName) {
        writeToRootCSV(fileName, modeChoiceInIteration, cumulativeModeChosenForModeChoice);
    }

    //csv for reference mode choice
    public void writeToRootCSVForReference(String fileBaseName) {

        final String csvFileName = outputFilename(fileBaseName, ".csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = cumulativeModeChosenForReference;

            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            double sum = benchMarkData.values().stream().reduce(Double::sum).orElse(0.0);
            StringBuilder builder = new StringBuilder("benchmark");
            for (String mode : modes) {
                if (benchMarkData.get(mode) != null) {
                    builder.append(",").append((benchMarkData.get(mode) * 100) / sum);
                } else {
                    builder.append(",0");
                }
            }
            out.write(builder.toString());
            out.newLine();
            int max = modeChoiceInIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            double[] sumInIteration = new double[max + 1];
            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Integer> modeCount = modeChoiceInIteration.get(iteration);
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            sumInIteration[iteration] += modeCount.get(mode);
                        }
                    }
                }
            }

            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Integer> modeCount = modeChoiceInIteration.get(iteration);
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
            out.flush();
        } catch (IOException e) {
            log.error("CSV generation failed.", e);
        }
    }

    private void writeModeChosenAvailableAlternativeCSV(Integer interation) {
        String csvFileName = iterationFilename(interation, "modeChosenAvailableAlternativesCount", ".csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            out.write("modeChosen, alternativesAvailable, numberOfTimes");
            out.newLine();
            modeChosenAvailableAlternativesCount.forEach((modeChosenAlternatives, count) -> {
                try {
                    out.write(modeChosenAlternatives.toCountString(count));
                    out.newLine();
                } catch (IOException exception) {
                    log.error(exception.getMessage(), exception);
                }
            });

            out.flush();
        } catch (IOException e) {
            log.error("CSV generation failed.", e);
        }
    }

    private Map<String, Double> benchmarkCsvLoader(String path) {
        Map<String, Double> benchmarkData = new HashMap<>();

        try (FileReader fileReader = new FileReader(path)) {
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            String line1 = bufferedReader.readLine();
            String line2 = bufferedReader.readLine();
            String[] mode = line1.split(",");
            String[] share = line2.split(",");
            for (int i = 1; i < mode.length; i++) {
                benchmarkData.put(mode[i], Double.parseDouble(share[i]));
            }
        } catch (Exception ex) {
            log.warn("Unable to load benchmark CSV via path '{}'", path);
        }
        return benchmarkData;
    }

    static class ModeChosenAvailableAlternatives {
        final String mode;
        final String availableModes;

        public ModeChosenAvailableAlternatives(String mode, String availableModes) {
            this.mode = mode;
            this.availableModes = availableModes;
        }

        public String toCountString(Integer count) {
            return mode + ", " + availableModes + ", " + count;
        }

        @Override
        public boolean equals(Object o) {

            if (o == this) return true;
            if (!(o instanceof ModeChosenAvailableAlternatives)) {
                return false;
            }

            ModeChosenAvailableAlternatives modeChosenAvailableAlternatives = (ModeChosenAvailableAlternatives) o;

            return modeChosenAvailableAlternatives.mode.equals(mode) &&
                    modeChosenAvailableAlternatives.availableModes.equals(availableModes);
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + mode.hashCode();
            result = 31 * result + availableModes.hashCode();
            return result;
        }
    }

    public static class ModeChosenComputation implements StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> {

        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Integer>>, Set<String>> stat) {
            List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(stat.getFirst().keySet());
            if (hoursList.isEmpty()) {
                return null;
            }
            final int maxHour = hoursList.get(hoursList.size() - 1);

            List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.getSecond());

            double[][] dataset = new double[stat.getSecond().size()][maxHour + 1];
            for (int i = 0; i < modesChosenList.size(); i++) {
                String modeChosen = modesChosenList.get(i);
                dataset[i] = getHoursDataPerOccurrenceAgainstMode(modeChosen, maxHour, stat.getFirst());
            }
            return dataset;
        }

        private double[] getHoursDataPerOccurrenceAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Integer>> stat) {
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            for (int hour = 0; hour <= maxHour; hour++) {
                Map<String, Integer> hourData = stat.getOrDefault(hour, Collections.emptyMap());
                modeOccurrencePerHour[hour] = defaultIfNull(hourData.get(modeChosen), 0);
            }
            return modeOccurrencePerHour;
        }
    }

}
