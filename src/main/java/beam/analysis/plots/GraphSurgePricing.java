package beam.analysis.plots;

import beam.agentsim.agents.RideHailSurgePricingManager;
import beam.agentsim.agents.SurgePriceBin;
import beam.analysis.via.CSVWriter;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import scala.collection.Iterator;
import scala.collection.mutable.ArrayBuffer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;

public class GraphSurgePricing {

    // The keys of the outer map represents binNumber
    // The inner map consists of category index to number of occurrence for each category
    // The categories are defined as buckets for occurrences of prices form 0-1, 1-2

    public static Map<Double, Map<Integer, Integer>> transformedBins = new HashMap<>();
    public static int binSize;
    public static int numberOfTimeBins;
    static int iterationNumber = 0;
    static String graphTitle = "Surge Price Level";
    static String xAxisLabel = "hour";
    static String yAxisLabel = "price level";

    public static Double max = null;
    public static Double min = null;

    public static List<Double> categoryKeys;


    public static double[] revenueDataSet;

    private static Set<String> tazIds = new TreeSet<>();

    public static void createGraph(RideHailSurgePricingManager surgePricingManager){

        //iterationNumber = itNo;
        transformedBins.clear();
        max = null;
        min = null;
        finalCategories.clear();

        binSize = surgePricingManager.timeBinSize();
        numberOfTimeBins = surgePricingManager.numberOfTimeBins();

        revenueDataSet = new double[numberOfTimeBins];

        processSurgePriceBinsMap(surgePricingManager);

        calculateCateogorySize();



        double[][] dataset = getDataset();

        List<String> categoriesKeys = getCategoriesKeys();


        drawGraph(dataset, categoriesKeys);

        drawRevenueGraph(revenueDataSet);

        iterationNumber++;
    }

    public static List<String> getCategoriesKeys(){

        if(min == max) {
            List<Double> categoriesList = new ArrayList<>();
            categoriesList.addAll(transformedBins.keySet());
            Collections.sort(categoriesList);

            List<String> categoriesStrings = new ArrayList<>();
            for (Double price : categoriesList) {
                //double _legend = Math.round(c * 100.0) / 100.0;
                categoriesStrings.add(price + "");
            }
            return categoriesStrings;
        }else{
            List<String> categoriesStrings= new ArrayList<>();

            for(Double key : categoryKeys){
                categoriesStrings.add(getRoundedNumber(key) + "");
            }
            return categoriesStrings;
        }
    }

    public static double[][] getDataset(){

        if(max != min) {

            buildCategoryKeys();

            processTransformedCategories();

            double[][] dataset = buildDatasetFromFinalCategories(finalCategories);

            return dataset;
        }else{

            double[][] dataset = buildDatasetFromTransformedCategories(transformedBins);

            return dataset;
        }
    }

    public static void processSurgePriceBinsMap(RideHailSurgePricingManager surgePricingManager){

        scala.collection.immutable.Map<String, scala.collection.mutable.ArrayBuffer<SurgePriceBin>> surgePriceBinsMap = surgePricingManager.surgePriceBins();
        Iterator mapIter = surgePriceBinsMap.keysIterator();

        while(mapIter.hasNext()) {

            String key = mapIter.next().toString();
            tazIds.add(key);
            ArrayBuffer<SurgePriceBin> bins  = surgePriceBinsMap.get(key).get();
            Iterator iter = bins.iterator();

            for (int i = 0; iter.hasNext(); i++) {
                SurgePriceBin bin = (SurgePriceBin) iter.next();
                processBin(i, bin);
            }
        }
    }

    public static void processBin(int binNumber, SurgePriceBin surgePriceBin){

        double revenue = surgePriceBin.currentIterationRevenue();
        revenueDataSet[binNumber] += revenue;
        //

        Double price = surgePriceBin.currentIterationSurgePriceLevel();

        Double roundedPrice = getRoundedNumber(price);

        max = (max == null || max < roundedPrice) ? roundedPrice : max;
        min = (min == null || min > roundedPrice) ? roundedPrice : min;

        Map<Integer, Integer> data = transformedBins.get(roundedPrice);

        if(data == null){
            data = new HashMap<>();
            data.put(binNumber, 1);
        }else{

            Integer frequency = data.get(binNumber);
            if(frequency == null){
                data.put(binNumber, 1);
            }else{
                data.put(binNumber, frequency + 1);
            }
        }

        transformedBins.put(roundedPrice, data);
    }

    private static int noOfCategories = 6;
    private static Double categorySize = null;

    public static void calculateCateogorySize(){
        categorySize = (max - min)/noOfCategories;
    }

    public static void buildCategoryKeys(){

        List<Double> _categoryKeys = new ArrayList<>();

        double minPrice = min;
        for(int i=0; i < noOfCategories; i++){

            _categoryKeys.add(minPrice);
            minPrice = minPrice + (categorySize);
        }

        categoryKeys = _categoryKeys;
    }

    public static int getPriceCategory(double price){

        int catIdxFound = -1;

        double startPrice = min;
        for(int i=0; i<noOfCategories; i++){

            double minPrice = startPrice;
            double maxPrice = minPrice + (categorySize);

            if(price >= minPrice && price <= maxPrice ){
                catIdxFound = i;
                break;
            }else{
                startPrice = maxPrice;
            }
        }

        return catIdxFound;
    }

    public static Map<Integer, Map<Integer, Integer>> finalCategories = new HashMap<>();

    public static void processTransformedCategories(){

        // determine the category based on key,
        // copy data from transformedBins to the final categories collection
        // if for that category we dont have data of bins just copy it
        // otherwise add it

        for(double k : transformedBins.keySet()){
            int idx = getPriceCategory(k);

            Map<Integer, Integer> sourceData = transformedBins.get(k);

            Map<Integer, Integer> data = finalCategories.get(idx);

            if(data == null){
                finalCategories.put(idx, sourceData);
            }else{

                for(int i=0; i<numberOfTimeBins; i++){

                    Integer sourceFrequency = sourceData.get(i);

                    Integer targetFrequencey = data.get(i);

                    if(sourceFrequency != null) {
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
        System.out.println("Done with final categories");
    }

    private static double[][] buildDatasetFromFinalCategories(Map<Integer, Map<Integer, Integer>> finalCategories) {

        double[][] dataset = new double[noOfCategories][numberOfTimeBins];


        for (int i =0 ; i<noOfCategories;i++) {

            Map<Integer, Integer> data = null;

            if(finalCategories.keySet().contains(i)){
                data = finalCategories.get(i);
            }

            if(data == null){
                double arr[] = new double[numberOfTimeBins];
                dataset[i] = arr;
            }else {

                double arr[] = new double[numberOfTimeBins];
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
        System.out.println("built the dataset");
        return dataset;
    }

    private static double[][] buildDatasetFromTransformedCategories(Map<Double, Map<Integer, Integer>> transformedCategories) {

        double[][] dataset = new double[transformedCategories.keySet().size()][numberOfTimeBins];

        List<Double> categoriesList = new ArrayList<>();
        categoriesList.addAll(transformedCategories.keySet());
        Collections.sort(categoriesList);

        int i=0;
        for (double key : categoriesList) {

            Map<Integer, Integer> data = transformedCategories.get(key);
            double arr[] = new double[numberOfTimeBins];
            for(int j=0; j<numberOfTimeBins;j++){
                Integer v = data.get(j);
                if(v == null){
                    arr[j] = 0;
                }else{
                    arr[j] = v;
                }
            }

            dataset[i++] = arr;
        }
        return dataset;
    }

    public static void drawGraph(double[][] _dataset, List<String> categoriesKeys){

        writePriceSurgeCsv(_dataset);
        CategoryDataset dataset = DatasetUtilities.createCategoryDataset("Categories ", "", _dataset);

        try {


            boolean legend = true;
            String fileName = "surge_pricing.png";
            final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset,graphTitle,xAxisLabel,yAxisLabel,fileName,legend);
            CategoryPlot plot = chart.getCategoryPlot();



            GraphUtils.plotLegendItems(plot, categoriesKeys, dataset.getRowCount());
            String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void drawRevenueGraph(double[] data) {

        DefaultCategoryDataset dataset = new DefaultCategoryDataset( );

        for(int i=0; i < data.length; i++){
            Double revenue = data[i];
            dataset.addValue(revenue, "revenue", "" + i);
        }

        JFreeChart chart = ChartFactory.createLineChart(
                "Ride Hail Revenue",
                "iteration","revenue",
                dataset,
                PlotOrientation.VERTICAL,
                false,true,false);

        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "revenue_graph.png");
        try {
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static Double getRoundedNumber(Double number){
        return Math.round(number * 100.0) / 100.0;
    }

    public static void writePriceSurgeCsv(double[][] dataset){

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "surge_pricing.csv");
        CSVWriter writer = new CSVWriter(csvFileName);

        try {
            BufferedWriter out = writer.getBufferedWriter();
            out.write("Categories");
            out.write(",");
            out.write("DataType");
            out.write(",");
            out.write("TazId");
            out.write(",");


            for(int i=0; i<dataset[0].length; i++){
                out.write("bin_" + i);
                out.write(",");
            }
            out.newLine();


            List<String> categoriesList = new ArrayList<>();
            categoriesList.addAll(getCategoriesKeys());
            Collections.sort(categoriesList);
            double diff = min;
            if(categoriesList.size() > 1)
                 diff = getRoundedNumber(Math.abs(min - Double.parseDouble(categoriesList.get(1))));

            for(int j= 0;j<categoriesList.size();j++){
                double category = Double.parseDouble(categoriesList.get(j));
                String strFormat = "";
                if(diff == category){
                    strFormat = category+"-"+diff;
                }else if(j+1 == categoriesList.size()){
                    strFormat = category+"-"+(category+diff);
                }
                else{
                    strFormat = category+"-"+categoriesList.get(j+1);
                }
                out.write(strFormat);
                out.write(",");
                out.write("price");
                out.write(",");
                out.write(tazIds.toArray()[j].toString());
                out.write(",");

                for(int i=0; i < dataset[j].length; i++){
                    out.write(dataset[j][i] + "");
                    out.write(",");
                }
                out.newLine();
            }

            out.flush();
            out.close();


        } catch (IOException e) {
            e.printStackTrace();
        }
    }




    ///////////////////////////////////////////////////////////
    /*
    Task 1 -
        We have surgepricebins collection.
        Each collection has bins of size binsize.
        Each bin has a price.
        We create a collection transformedbins
        In transformedbins, the key is the price, the value is a map [bin, frequency]
        This shows that for this price we have this frequency for this particular bin.


     */
}
