package beam.analysis.plots;

import beam.agentsim.agents.rideHail.RideHailSurgePricingManager;
import beam.agentsim.agents.rideHail.SurgePriceBin;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.Iterator;
import scala.collection.mutable.ArrayBuffer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;


public class GraphSurgePricing {

    // The keys of the outer map represents binNumber
    // The inner map consists of category index to number of occurrence for each category
    // The categories are defined as buckets for occurrences of prices form 0-1, 1-2

    private Logger log = LoggerFactory.getLogger(GraphSurgePricing.class);

    private int iterationNumber = 0;

    private  Map<Double, Map<Integer, Integer>> transformedBins = new HashMap<>();
    private  int binSize;
    private  int numberOfTimeBins;
    private  String graphTitle = "Ride Hail Surge Price Level";
    private  String xAxisLabel = "timebin";
    private  String yAxisLabel = "price level";
    private  int noOfCategories = 0;
    private  Double categorySize = null;
    private   Double max = null;
    private   Double min = null;

    private   List<Double> categoryKeys;


    private  double[] revenueDataSet;

    private  Set<String> tazIds = new TreeSet<>();

    private  Map<String, double[][]> tazDataset = new TreeMap<>();

    private  String graphImageFile = "";
    private  String surgePricingCsvFileName = "";
    private  String surgePricingAndRevenueWithTaz = "";
    private  String revenueGraphImageFile =  "";
    private  String revenueCsvFileName =  "";


    public GraphSurgePricing(){

    }

    public  void createGraph(RideHailSurgePricingManager surgePricingManager){
        noOfCategories = surgePricingManager.numberOfCategories();
        iterationNumber = surgePricingManager.getIterationNumber();
        //iterationNumber = itNo;
        tazDataset.clear();
        transformedBins.clear();
        max = null;
        min = null;
        finalCategories.clear();
        tazIds.clear();


        final int iNo = iterationNumber;
        graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iNo, "rideHailSurgePriceLevel.png");
        surgePricingCsvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iNo, "rideHailSurgePriceLevel.csv");
        surgePricingAndRevenueWithTaz = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iNo, "taz_rideHailSurgePriceLevel.csv");
        revenueGraphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iNo, "rideHailRevenue.png");
        revenueCsvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iNo, "rideHailRevenue.csv");


        binSize = surgePricingManager.timeBinSize();
        numberOfTimeBins = surgePricingManager.numberOfTimeBins();

        revenueDataSet = new double[numberOfTimeBins];

        processSurgePriceBinsMap(surgePricingManager);

        calculateCateogorySize();



        double[][] dataset = getDataset();

        List<String> categoriesKeys = getCategoriesKeys();


        drawGraph(dataset, categoriesKeys);

        drawRevenueGraph(revenueDataSet);

        writeTazCsv(tazDataset);

        writeRevenueCsv(revenueDataSet);


    }

    public  List<String> getCategoriesKeys(){

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

    public  double[][] getDataset(){

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

    public  void processSurgePriceBinsMap(RideHailSurgePricingManager surgePricingManager){

        scala.collection.immutable.Map<String, scala.collection.mutable.ArrayBuffer<SurgePriceBin>> surgePriceBinsMap = surgePricingManager.surgePriceBins();
        Iterator mapIter = surgePriceBinsMap.keysIterator();

        while(mapIter.hasNext()) {

            String key = mapIter.next().toString();
            tazIds.add(key);



            ArrayBuffer<SurgePriceBin> bins  = surgePriceBinsMap.get(key).get();
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

    public  void processBin(int binNumber, SurgePriceBin surgePriceBin){

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



    public  void calculateCateogorySize(){
        categorySize = (max - min)/noOfCategories;
    }

    public  void buildCategoryKeys(){

        List<Double> _categoryKeys = new ArrayList<>();

        double minPrice = min;
        for(int i=0; i < noOfCategories; i++){

            _categoryKeys.add(minPrice);
            minPrice = minPrice + (categorySize);
        }

        categoryKeys = _categoryKeys;
    }

    public  int getPriceCategory(double price){

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

    public  Map<Integer, Map<Integer, Integer>> finalCategories = new HashMap<>();

    public  void processTransformedCategories(){

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
        log.info("Done with final categories");
    }

    private  double[][] buildDatasetFromFinalCategories(Map<Integer, Map<Integer, Integer>> finalCategories) {

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
       log.info("built the dataset");
        return dataset;
    }

    private  double[][] buildDatasetFromTransformedCategories(Map<Double, Map<Integer, Integer>> transformedCategories) {

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

    public  void drawGraph(double[][] _dataset, List<String> categoriesKeys){

        writePriceSurgeCsv(_dataset);
        CategoryDataset dataset = DatasetUtilities.createCategoryDataset("Categories ", "", _dataset);


        List<String> _categoriesKeys = new ArrayList<>();
        _categoriesKeys.addAll(categoriesKeys);
        int lastIndex = _categoriesKeys.size() - 1;
        String lastValue = _categoriesKeys.get(lastIndex);
        lastValue = lastValue + "-" + max;
        _categoriesKeys.set(lastIndex, lastValue);

        try {


            boolean legend = true;
            String fileName = "surge_pricing.png";
            final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset,graphTitle,xAxisLabel,yAxisLabel,fileName,legend);
            CategoryPlot plot = chart.getCategoryPlot();



            GraphUtils.plotLegendItems(plot, _categoriesKeys, dataset.getRowCount());


            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public  void drawRevenueGraph(double[] data) {

        DefaultCategoryDataset dataset = new DefaultCategoryDataset( );

        for(int i=0; i < data.length; i++){
            Double revenue = data[i];
            dataset.addValue(revenue, "revenue", "" + i);
        }

        JFreeChart chart = ChartFactory.createLineChart(
                "Ride Hail Revenue",
                "timebin","revenue($)",
                dataset,
                PlotOrientation.VERTICAL,
                false,true,false);

        try {
            GraphUtils.saveJFreeChartAsPNG(chart, revenueGraphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public  Double getRoundedNumber(Double number){
        return Math.round(number * 100.0) / 100.0;
    }

    public  void writePriceSurgeCsv(double[][] dataset){



        try {
            BufferedWriter out = new BufferedWriter(new FileWriter( new File(surgePricingCsvFileName)));
            //BufferedWriter out = writer.getBufferedWriter();
            out.write("Categories");
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
                /*out.write("price");
                out.write(",");
                out.write(tazIds.toArray()[j].toString());
                out.write(",");*/

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

    public  void writeTazCsv(Map<String, double[][]> dataset){



        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(new File(surgePricingAndRevenueWithTaz)));

            out.write("TazId");
            out.write(",");

            out.write("DataType");
            out.write(",");


            for(int i = 0; i < numberOfTimeBins; i++){
                out.write("bin_" + i);
                out.write(",");
            }
            out.newLine();



            for(String tazId : dataset.keySet()){
                double[][] data = dataset.get(tazId);

                double[] prices = data[0];
                double[] revenues = data[1];

                out.write(tazId);
                out.write(",");

                out.write("pricelevel");
                out.write(",");

                for(int i = 0; i< numberOfTimeBins; i++){
                    out.write(prices[i] + "");
                    out.write(",");
                }
                out.newLine();

                out.write(tazId);
                out.write(",");

                out.write("revenue");
                out.write(",");

                for(int i = 0; i< numberOfTimeBins; i++){
                    out.write(revenues[i] + "");
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

    public  void writeRevenueCsv(double[] revenueDataSet){



        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(new File(revenueCsvFileName)));



            for(int i = 0; i < numberOfTimeBins; i++){
                out.write("bin_" + i);
                out.write(",");
            }
            out.newLine();



            for(double revenue : revenueDataSet){
                out.write( revenue + "");
                out.write(",");
            }
            out.newLine();



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
