package beam.analysis.plots;

import beam.agentsim.agents.ridehail.RideHailSurgePricingManager;
import beam.agentsim.agents.ridehail.SurgePriceBin;
import com.google.inject.Inject;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.Iterator;
import scala.collection.mutable.ArrayBuffer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.range;


public class GraphSurgePricing implements ControlerListener, IterationEndsListener {

    // The keys of the outer map represents binNumber
    // The inner map consists of category index to number of occurrence for each category
    // The categories are defined as buckets for occurrences of prices form 0-1, 1-2

    private final Logger log = LoggerFactory.getLogger(GraphSurgePricing.class);

    private final Map<Double, Map<Integer, Integer>> transformedBins = new HashMap<>();
    private final int numberOfTimeBins;
    private static final String graphTitle = "Ride Hail Surge Price Level";
    private static final String xAxisLabel = "timebin";
    private static final String yAxisLabel = "price level";
    private final int noOfCategories;
    private Double categorySize = null;
    private Double max;
    private Double min;

    private double[] revenueDataSet;

    private final Map<String, double[][]> tazDataset = new TreeMap<>();

    private String graphImageFile = "";
    private String surgePricingCsvFileName = "";
    private String surgePricingAndRevenueWithTaz = "";
    private String revenueGraphImageFile = "";
    private String revenueCsvFileName = "";
    private final RideHailSurgePricingManager surgePricingManager;
    private final boolean writeGraph;

    @Inject
    public GraphSurgePricing(RideHailSurgePricingManager surgePricingManager) {
        this.surgePricingManager = surgePricingManager;
        noOfCategories = this.surgePricingManager.numberOfCategories();

        max = null;
        min = null;

        numberOfTimeBins = this.surgePricingManager.numberOfTimeBins();
        this.writeGraph = surgePricingManager.beamServices().beamConfig().beam().outputs().writeGraphs();
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {

        tazDataset.clear();
        transformedBins.clear();
        revenueDataSet = new double[numberOfTimeBins];

        final int iNo = event.getIteration();

        OutputDirectoryHierarchy odh = event.getServices().getControlerIO();

        graphImageFile = odh.getIterationFilename(iNo, "rideHailSurgePriceLevel.png");
        surgePricingCsvFileName = odh.getIterationFilename(iNo, "rideHailSurgePriceLevel.csv");
        surgePricingAndRevenueWithTaz = odh.getIterationFilename(iNo, "tazRideHailSurgePriceLevel.csv.gz");
        revenueGraphImageFile = odh.getIterationFilename(iNo, "rideHailRevenue.png");
        revenueCsvFileName = odh.getIterationFilename(iNo, "rideHailRevenue.csv");

        this.createGraphs();

        // for next iteration
        this.surgePricingManager.updateSurgePriceLevels();
    }

    private void createGraphs() {

        processSurgePriceBinsMap(surgePricingManager);

        if (!min.equals(max)) {

            calculateCateogorySize();
            List<String> categoriesKeys = getCategoriesKeys(transformedBins, true);
            double[][] dataset = getDataset(true);
            writePriceSurgeCsv(dataset, categoriesKeys, true);
            if (writeGraph) {
                drawGraph(dataset, categoriesKeys, true);
                drawHistogram(dataset, categoriesKeys, true);
            }
        } else {

            List<String> categoriesKeys = getCategoriesKeys(transformedBins, false);
            double[][] dataset = getDataset(false);
            writePriceSurgeCsv(dataset, categoriesKeys, false);
            if (writeGraph) {
                drawGraph(dataset, categoriesKeys, false);
                drawHistogram(dataset, categoriesKeys, true);
            }

        }
        if (writeGraph) {
            drawRevenueGraph(revenueDataSet);
        }

        writeTazCsv(tazDataset);

        writeRevenueCsv(revenueDataSet);
    }

    private List<String> getCategoriesKeys(Map<Double, Map<Integer, Integer>> transformedBins, boolean categorize) {

        List<String> categoriesStrings = new ArrayList<>();

        if (!categorize) {
            List<Double> categoriesList = new ArrayList<>(transformedBins.keySet());
            Collections.sort(categoriesList);

//            categoriesStrings = categoriesList.stream().map(String::valueOf).collect(Collectors.toList());
            for (Double price : categoriesList) {
                categoriesStrings.add(price + "");
            }
        } else {
//            categoriesStrings = buildCategoryKeys().stream().map(String::valueOf).collect(Collectors.toList());
            for (Double key : buildCategoryKeys()) {
                categoriesStrings.add(getRoundedNumber(key) + "");
            }
        }

        return categoriesStrings;
    }

    private double[][] getDataset(boolean categorize) {
        if (categorize) {
            Map<Integer, Map<Integer, Integer>> finalCategories = convertTransformedBinsToCategories(transformedBins);
            return buildDatasetFromCategories(finalCategories);
        } else {
            return buildDatasetFromTransformedBins(transformedBins);
        }
    }

    private void processSurgePriceBinsMap(RideHailSurgePricingManager surgePricingManager) {

        scala.collection.immutable.Map<String, scala.collection.mutable.ArrayBuffer<SurgePriceBin>> surgePriceBinsMap = surgePricingManager.surgePriceBins();
        Iterator mapIter = surgePriceBinsMap.keysIterator();

        while (mapIter.hasNext()) {

            String key = mapIter.next().toString();

            ArrayBuffer<SurgePriceBin> bins = surgePriceBinsMap.get(key).get();
            Iterator iter = bins.iterator();

            double[][] _tazDataset = new double[2][numberOfTimeBins];

            for (int i = 0; iter.hasNext(); i++) {
                SurgePriceBin bin = (SurgePriceBin) iter.next();

                double price = bin.currentIterationSurgePriceLevel();
                double revenue = bin.currentIterationRevenue();

                _tazDataset[0][i] = price;
                _tazDataset[1][i] = revenue;

                processBin(i, bin);
            }

            tazDataset.put(key, _tazDataset);
        }
    }

    private void processBin(int binNumber, SurgePriceBin surgePriceBin) {

        double revenue = surgePriceBin.currentIterationRevenue();
        revenueDataSet[binNumber] += revenue;

        Double price = surgePriceBin.currentIterationSurgePriceLevel();
        Double roundedPrice = getRoundedNumber(price);

        max = (max == null || max < roundedPrice) ? roundedPrice : max;
        min = (min == null || min > roundedPrice) ? roundedPrice : min;

        Map<Integer, Integer> data = transformedBins.get(roundedPrice);

        if (data == null) {
            data = new HashMap<>();
            data.put(binNumber, 1);
        } else {
            data.merge(binNumber, 1, (a, b) -> a + b);
        }

        transformedBins.put(roundedPrice, data);
    }


    private void calculateCateogorySize() {
        categorySize = (max - min) / noOfCategories;
    }

    private List<Double> buildCategoryKeys() {

        List<Double> _categoryKeys = new ArrayList<>();
        double minPrice = min;
        for (int i = 0; i < noOfCategories; i++) {

            _categoryKeys.add(minPrice);
            minPrice = minPrice + (categorySize);
        }

        return _categoryKeys;
    }

    private int getPriceCategory(double price) {

        int catIdxFound = -1;
        double startPrice = min;
        for (int i = 0; i < noOfCategories; i++) {

            double minPrice = startPrice;
            double maxPrice = minPrice + (categorySize);

            if (price >= minPrice && price <= maxPrice) {
                catIdxFound = i;
                break;
            } else {
                startPrice = maxPrice;
            }
        }

        return catIdxFound;
    }


    private Map<Integer, Map<Integer, Integer>> convertTransformedBinsToCategories(Map<Double, Map<Integer, Integer>> transformedBins) {

        // determine the category based on key,
        // copy data from transformedBins to the final categories collection
        // if for that category we dont have data of bins just copy it
        // otherwise add it

        Map<Integer, Map<Integer, Integer>> finalCategories = new HashMap<>();


        for (double k : transformedBins.keySet()) {
            int idx = getPriceCategory(k);
            Map<Integer, Integer> sourceData = transformedBins.get(k);
            Map<Integer, Integer> data = finalCategories.get(idx);

            if (data == null) {
                finalCategories.put(idx, sourceData);
            } else {
                for (int i = 0; i < numberOfTimeBins; i++) {

                    Integer sourceFrequency = sourceData.get(i);
                    Integer targetFrequencey = data.get(i);

                    if (sourceFrequency != null) {
                        if (targetFrequencey == null) {
                            data.put(i, sourceFrequency);
                        } else {
                            data.put(i, sourceFrequency + targetFrequencey);
                        }
                    }
                }

                finalCategories.put(idx, data);
            }
        }
        log.info("Done with final categories");
        return finalCategories;
    }

    private double[][] buildDatasetFromCategories(Map<Integer, Map<Integer, Integer>> finalCategories) {

        double[][] dataset = new double[noOfCategories][numberOfTimeBins];

        for (int i = 0; i < noOfCategories; i++) {

            Map<Integer, Integer> data = null;

            if (finalCategories.containsKey(i)) {
                data = finalCategories.get(i);
            }

            if (data == null) {
                dataset[i] = new double[numberOfTimeBins];
            } else {
                double[] arr = new double[numberOfTimeBins];
                for (int j = 0; j < numberOfTimeBins; j++) {
                    Integer v = data.get(j);
                    if (v == null) {
                        arr[j] = 0;
                    } else {
                        arr[j] = v;
                    }
                }

                dataset[i] = arr;
            }
        }
        log.info("built the dataset");
        return dataset;
    }

    private double[][] buildDatasetFromTransformedBins(Map<Double, Map<Integer, Integer>> transformedCategories) {

        double[][] dataset = new double[transformedCategories.keySet().size()][numberOfTimeBins];
        List<Double> categoriesList = new ArrayList<>(transformedCategories.keySet());
        Collections.sort(categoriesList);

        int i = 0;
        for (double key : categoriesList) {

            Map<Integer, Integer> data = transformedCategories.get(key);
            double[] arr = new double[numberOfTimeBins];
            for (int j = 0; j < numberOfTimeBins; j++) {
                Integer v = data.get(j);
                if (v == null) {
                    arr[j] = 0;
                } else {
                    arr[j] = v;
                }
            }

            dataset[i++] = arr;
        }
        return dataset;
    }

    private void drawGraph(double[][] _dataset, List<String> categoriesKeys, boolean categorize) {
        CategoryDataset dataset = GraphUtils.createCategoryDataset("Categories ", "", _dataset);
        List<String> _categoriesKeys = new ArrayList<>(categoriesKeys);
        int lastIndex = _categoriesKeys.size() - 1;
        String lastValue = _categoriesKeys.get(lastIndex);
        lastValue = lastValue + "-" + max;
        _categoriesKeys.set(lastIndex, lastValue);

        try {
            String fileName = graphImageFile;

            final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisLabel, yAxisLabel, true);
            CategoryPlot plot = chart.getCategoryPlot();
            GraphUtils.plotLegendItems(plot, _categoriesKeys, dataset.getRowCount());
            GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private void drawRevenueGraph(double[] data) {
        CategoryDataset dataset = GraphUtils.createCategoryDataset("revenue", "", data);

        JFreeChart chart = GraphUtils.createLineChartWithDefaultSettings(
                dataset,
                "Ride Hail Revenue",
                "timebin",
                "revenue($)",
                false,
                true);

        try {
            GraphUtils.saveJFreeChartAsPNG(chart, revenueGraphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private void writePriceSurgeCsv(double[][] dataset, List<String> categoriesList, boolean categorize) {
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(surgePricingCsvFileName)))) {
            out.write("Categories,PriceLevel,Hour");
            out.newLine();

            if (categorize) {
                double diff = min;
                if (categoriesList.size() > 1)
                    diff = getRoundedNumber(Math.abs(min - Double.parseDouble(categoriesList.get(1))));

                for (int j = 0; j < categoriesList.size(); j++) {
                    double category = Double.parseDouble(categoriesList.get(j));
                    String strFormat = category + "-";
                    if (diff == category) {
                        strFormat += diff;
                    } else if (j + 1 == categoriesList.size()) {
                        strFormat += (category + diff);
                    } else {
                        strFormat += categoriesList.get(j + 1);
                    }

                    double[] priceLevels = dataset[j];

                    for (int i = 0; i < priceLevels.length; i++) {
                        out.write(strFormat + "," + getRoundedNumber(priceLevels[i]) + "," + i);
                        out.newLine();
                    }
                }
            } else {
                for (int j = 0; j < categoriesList.size(); j++) {
                    double[] priceLevels = dataset[j];

                    for (int i = 0; i < priceLevels.length; i++) {
                        out.write(categoriesList.get(j) + "," + getRoundedNumber(priceLevels[i]) + "," + i);
                        out.newLine();
                    }
                }
            }

            out.flush();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private void writeTazCsv(Map<String, double[][]> dataset) {
        try (BufferedWriter out = IOUtils.getBufferedWriter(surgePricingAndRevenueWithTaz)) {
            out.write("TazId,DataType,Value,Hour");
            out.newLine();

            for (String tazId : dataset.keySet()) {
                double[][] data = dataset.get(tazId);

                double[] priceLevels = data[0];
                double[] revenues = data[1];

                for (int i = 0; i < priceLevels.length; i++) {
                    out.write(tazId + ",pricelevel," + getRoundedNumber(priceLevels[i]) + "," + (i + 1));
                    out.newLine();

                    out.write(tazId + ",revenue," + getRoundedNumber(revenues[i]) + "," + (i + 1));
                    out.newLine();
                }
            }

            out.flush();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private void writeRevenueCsv(double[] revenueDataSet) {
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(revenueCsvFileName)))) {
            out.write("Revenue,Hour");
            out.newLine();

            for (int i = 0; i < revenueDataSet.length; i++) {
                out.write(getRoundedNumber(revenueDataSet[i]) + "," + (i));
                out.newLine();
            }
            out.newLine();
            out.flush();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private Double getRoundedNumber(Double number) {
        return Math.round(number * 100.0) / 100.0;
    }

    private void drawHistogram(double[][] dataset, List<String> categoriesList, boolean categorize) {

        //double[] prices = new double[dataset.length];
        List<Double> prices = new ArrayList<>();

        for (int i = 0; i < dataset.length; i++) {
            double price = Double.parseDouble(categoriesList.get(i));

            for (int j = 0; j < dataset[i].length; j++) {

                double f = dataset[i][j];
                for (int k = 0; k < f; k++) {
                    prices.add(price);
                }
            }
        }

        //System.outWriter.println("Frequencies : " + Arrays.toString(frequencies));

        // Création des datasets
        HistogramDataset histogramDataset = new HistogramDataset();
        histogramDataset.setType(HistogramType.FREQUENCY);

//        String [] stockArr = stockList.toArray(new String[stockList.size()]);
        double[] _prices = new double[prices.size()];
        for (int i = 0; i < prices.size(); i++) {
            _prices[i] = prices.get(i);
        }

        histogramDataset.addSeries("Ride Hailing Price Histogram", _prices, 10);

        // Création de l'histogramme
        JFreeChart chart = ChartFactory.createHistogram("", null, null, histogramDataset,
                PlotOrientation.VERTICAL, true, true, false);

        String fileName = graphImageFile.replace(".png", "Histogram.png");
        //GraphUtils.plotLegendItems(plot, _categoriesKeys, dataset.getRowCount());

        try {
            GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

}
