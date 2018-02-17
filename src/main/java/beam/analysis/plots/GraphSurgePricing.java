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

    public static double[] revenueDataSet;

    public static void createGraph(RideHailSurgePricingManager surgePricingManager){

        //iterationNumber = itNo;
        transformedBins.clear();

        binSize = surgePricingManager.timeBinSize();
        numberOfTimeBins = surgePricingManager.numberOfTimeBins();

        revenueDataSet = new double[numberOfTimeBins];

        processSurgePriceBinsMap(surgePricingManager);

        drawGraph();

        drawRevenueGraph(revenueDataSet);

        iterationNumber++;
    }

    public static void processSurgePriceBinsMap(RideHailSurgePricingManager surgePricingManager){
        scala.collection.immutable.Map<String, scala.collection.mutable.ArrayBuffer<SurgePriceBin>> surgePriceBinsMap = surgePricingManager.surgePriceBins();
        Iterator mapIter = surgePriceBinsMap.keysIterator();

        while(mapIter.hasNext()) {

            String key = mapIter.next().toString();
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

        Double price = surgePriceBin.currentIterationSurgePriceLevel();

        Double roundedPrice = getRoundedNumber(price);

        Map<Integer, Integer> data = transformedBins.get(roundedPrice);

        if(data == null){
            data = new HashMap<>();
            data.put(binNumber, 1);
        }else{

            Integer occurrence = data.get(binNumber);
            if(occurrence == null){
                data.put(binNumber, 1);
            }else{
                data.put(binNumber, occurrence + 1);
            }
        }

        transformedBins.put(roundedPrice, data);
    }

    private static double[][] buildDataset() {

        double[][] dataset = new double[transformedBins.keySet().size()][numberOfTimeBins];

        List<Double> categoriesList = new ArrayList<>();
        categoriesList.addAll(transformedBins.keySet());
        Collections.sort(categoriesList);

        int i=0;
        for (double key : categoriesList) {

            Map<Integer, Integer> data = transformedBins.get(key);
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

    public static void drawGraph(){

        double[][] dataset = buildDataset();
        writePriceSurgeCsv(dataset);
        CategoryDataset ds = DatasetUtilities.createCategoryDataset("Categories ", "", dataset);

        try {
            createSurgePricingGraph(ds, iterationNumber);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void createSurgePricingGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        boolean legend = true;
        String fileName = "surge_pricing.png";
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset,graphTitle,xAxisLabel,yAxisLabel,fileName,legend);
        CategoryPlot plot = chart.getCategoryPlot();

        List<Double> categoriesList = new ArrayList<>();
        categoriesList.addAll(transformedBins.keySet());
        Collections.sort(categoriesList);

        List<String> categoriesStrings = new ArrayList<>();
        for(Double price : categoriesList){
            //double _legend = Math.round(c * 100.0) / 100.0;
            categoriesStrings.add(price + "");
        }


        GraphUtils.plotLegendItems(plot, categoriesStrings, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
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

            for(int i=0; i<dataset[0].length; i++){
                out.write("bin_" + i);
                out.write(",");
            }
            out.newLine();


            List<Double> categoriesList = new ArrayList<>();
            categoriesList.addAll(transformedBins.keySet());
            Collections.sort(categoriesList);

            List<String> categoriesStrings = new ArrayList<>();

            int j = 0;
            for(Double c : categoriesList){
                double _legend = Math.round(c * 100.0) / 100.0;
                out.write(_legend + "");
                out.write(",");

                for(int i=0; i < dataset[j].length; i++){
                    out.write(dataset[j][i] + "");
                    out.write(",");
                }
                out.newLine();
                j++;

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
