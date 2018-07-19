package beam.analysis.physsim;

import beam.sim.config.BeamConfig;
import beam.utils.BeamCalcLinkStats;
import org.jfree.chart.*;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class PhyssimCalcLinkStats {

    private Network network;
    private OutputDirectoryHierarchy controlerIO;

    private BeamCalcLinkStats linkStats;
    private VolumesAnalyzer volumes;

    public static int noOfBins = 24;
    public static int binSize = 3600;


    public static final List<Color> colors = new ArrayList<>();


    /**
     * The outer map contains the relativeSpeed a double value as the key that defines a relativeSpeed category.
     * The inner map contains the bin id as the key and the frequency as the value for the particular relativeSpeed category.
     */
    Map<Double, Map<Integer, Integer>> relativeSpeedFrequenciesPerBin = new HashMap<>();

    BeamConfig beamConfig;

    // Static Initializer
    static {

        colors.add(Color.GREEN);
        colors.add(Color.BLUE);
        colors.add(Color.GRAY);
        colors.add(Color.PINK);
        colors.add(Color.RED);
        colors.add(Color.MAGENTA);
        colors.add(Color.BLACK);
        colors.add(Color.YELLOW);
        colors.add(Color.CYAN);


    }

    public PhyssimCalcLinkStats(Network network, OutputDirectoryHierarchy controlerIO, BeamConfig beamConfig) {
        this.network = network;
        this.controlerIO = controlerIO;
        this.beamConfig = beamConfig;

        if(isNotTestMode())
            this.binSize = this.beamConfig.beam().physsim().linkstatsBinSize();

        linkStats = new BeamCalcLinkStats(network);
    }


    public void notifyIterationEnds(int iteration, TravelTimeCalculator travelTimeCalculator) {

        linkStats.addData(volumes, travelTimeCalculator.getLinkTravelTimes());
        processData(iteration, travelTimeCalculator);
        CategoryDataset dataset = buildAndGetGraphCategoryDataset();
        if(this.controlerIO != null) {
            if(isNotTestMode() && writeLinkstats(iteration)) {
                linkStats.writeFile(this.controlerIO.getIterationFilename(iteration, "linkstats.csv.gz"));
            }
            createModesFrequencyGraph(dataset, iteration);
        }
    }

    private boolean isNotTestMode() {
        return beamConfig != null;
    }


    private boolean writeLinkstats(int iterationNumber) {
        return writeInIteration(iterationNumber, beamConfig.beam().physsim().linkstatsWriteInterval());
    }

    private boolean writeInIteration(int iterationNumber, int interval) {
        return interval == 1 || (interval > 0 && iterationNumber % interval == 0);
    }

    private void processData(int iteration, TravelTimeCalculator travelTimeCalculator) {


        TravelTime travelTime = travelTimeCalculator.getLinkTravelTimes();

        for(int idx = 0; idx < noOfBins; idx++) {


            for (Link link : this.network.getLinks().values()) {


                double freeSpeed = link.getFreespeed(idx * binSize);

                double linkLength = link.getLength();

                double averageTime = travelTime.getLinkTravelTime(link, idx * binSize, null, null);

                double averageSpeed = linkLength / averageTime;

                double averageSpeedToFreeSpeedRatio = averageSpeed / freeSpeed;

                double relativeSpeed = Math.round(averageSpeedToFreeSpeedRatio * 10) / 10;

                Map<Integer, Integer> hoursDataMap = relativeSpeedFrequenciesPerBin.get(relativeSpeed);

                if(hoursDataMap != null) {
                    Integer frequency = hoursDataMap.get(idx);
                    if(frequency != null){
                        hoursDataMap.put(idx, frequency + 1);
                    }else{
                        hoursDataMap.put(idx, 1);
                    }
                }else{
                    hoursDataMap = new HashMap<>();
                    hoursDataMap.put(idx, 1);
                }

                relativeSpeedFrequenciesPerBin.put(relativeSpeed, hoursDataMap);
            }
        }
    }
    public double getRelativeSpeedOfSpecificHour(int relativeSpeedCategoryIndex,int hour){
        double[][] dataset = buildModesFrequencyDataset();
        double[] hoursData = dataset[relativeSpeedCategoryIndex];
        return hoursData[hour];
    }
    public double getRelativeSpeedCountOfSpecificCategory(int relativeSpeedCategoryIndex){
        double[][] dataset = buildModesFrequencyDataset();
        double[] hoursData = dataset[relativeSpeedCategoryIndex];
        double count = 0;
        for(double hourCount:hoursData){
            count = count + hourCount;
        }
        return count;
    }


    private CategoryDataset buildAndGetGraphCategoryDataset(){
        double[][] dataset = buildModesFrequencyDataset();
        return DatasetUtilities.createCategoryDataset("Relative Speed", "", dataset);
    }
    public List<Double> getSortedListRelativeSpeedCategoryList(){
        List<Double> relativeSpeedsCategoriesList = new ArrayList<>(relativeSpeedFrequenciesPerBin.keySet());
        Collections.sort(relativeSpeedsCategoriesList);
        return relativeSpeedsCategoriesList;
    }
    private double[][] buildModesFrequencyDataset() {

        List<Double> relativeSpeedsCategoriesList = getSortedListRelativeSpeedCategoryList();


        double[][] dataset = new double[relativeSpeedsCategoriesList.size()][noOfBins];

        for (int i = 0; i < relativeSpeedsCategoriesList.size(); i++) {

            Double relativeSpeedCategory = relativeSpeedsCategoriesList.get(i);
            Map<Integer, Integer> relativeSpeedBins = relativeSpeedFrequenciesPerBin.get(relativeSpeedCategory);

            double[] relativeSpeedFrequencyPerHour = new double[noOfBins];
            int index = 0;

            for (int binIndex = 0; binIndex < noOfBins; binIndex++) {
                Integer hourFrequency = relativeSpeedBins.get(binIndex);
                if (hourFrequency != null) {
                    relativeSpeedFrequencyPerHour[index] = hourFrequency;
                } else {
                    relativeSpeedFrequencyPerHour[index] = 0;
                }
                index = index + 1;
            }
            dataset[i] = relativeSpeedFrequencyPerHour;
        }

        return  dataset;
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) {

        String plotTitle = "Relative Network Link Speeds";
        String xaxis = "Hour";
        String yaxis = "# of network links";
        int width = 800;
        int height = 600;
        boolean show = true;
        boolean toolTips = false;
        boolean urls = false;
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        String graphImageFile = controlerIO.getIterationFilename(iterationNumber, "relative_speeds.png");

        final JFreeChart chart = ChartFactory.createStackedBarChart(
                plotTitle, xaxis, yaxis,
                dataset, orientation, show, toolTips, urls);

        chart.setBackgroundPaint(new Color(255, 255, 255));
        CategoryPlot plot = chart.getCategoryPlot();

        LegendItemCollection legendItems = new LegendItemCollection();


        java.util.List<Double> relativeSpeedsCategoriesList = new ArrayList<>();
        relativeSpeedsCategoriesList.addAll(relativeSpeedFrequenciesPerBin.keySet());
        Collections.sort(relativeSpeedsCategoriesList);



        for (int i = 0; i < dataset.getRowCount(); i++) {


            legendItems.add(new LegendItem(relativeSpeedsCategoriesList.get(i).toString(), getColor(i)));

            plot.getRenderer().setSeriesPaint(i, getColor(i));

        }
        plot.setFixedLegendItems(legendItems);


        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }

    private Color getColor(int i) {
        if (i < colors.size()) {
            return colors.get(i);
        } else {
            return getRandomColor();
        }
    }

    private Color getRandomColor() {

        Random rand = new Random();

        float r = rand.nextFloat();
        float g = rand.nextFloat();
        float b = rand.nextFloat();

        Color randomColor = new Color(r, g, b);
        return randomColor;
    }

    public void notifyIterationStarts(EventsManager eventsManager) {

        this.linkStats.reset();
        volumes = new VolumesAnalyzer(3600, 24 * 3600 - 1, network);
        eventsManager.addHandler(volumes);

        this.relativeSpeedFrequenciesPerBin.clear();
    }
}
