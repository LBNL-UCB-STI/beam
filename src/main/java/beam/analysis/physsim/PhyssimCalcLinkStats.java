package beam.analysis.physsim;

import org.jfree.chart.*;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;

public class PhyssimCalcLinkStats {

    private Network network;
    private OutputDirectoryHierarchy controlerIO;

    public static final List<Color> colors = new ArrayList<>();


    //Map<Id<Link>, Double> linkAvgSpeedToFreeSpeedRatios = new TreeMap<>();

    // Map of categories to hourly bins.
    // Categories are relative speeds in double
    Map<Double, Map<Integer, Integer>> relativeSpeedFrequenciesPerBin = new HashMap<>();


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

    public PhyssimCalcLinkStats(Network network, OutputDirectoryHierarchy controlerIO) {
        this.network = network;
        this.controlerIO = controlerIO;
    }


    public void notifyIterationEnds(int iteration, TravelTimeCalculator travelTimeCalculator) {

        processData(iteration, travelTimeCalculator);

        //drawGraph();
        CategoryDataset dataset = buildModesFrequencyDataset();
        createModesFrequencyGraph(dataset, iteration);
    }

    private void processData(int iteration, TravelTimeCalculator travelTimeCalculator) {

        System.out.println(getClass().getName() + " iteration -> " + iteration);

        TravelTime travelTime = travelTimeCalculator.getLinkTravelTimes();

        //int noOfBins = travelTimeCalculator.getNumSlots();

        int noOfBins = 24;
        int binSize = 3600;

        for(int idx = 0; idx < noOfBins; idx++) {

            Map<Double, Integer> relativeSpeeds = new HashMap<>();

            /*Map<Double, Integer> relativeSpeeds = relativeSpeedFrequenciesPerBin.get(idx);
            if (relativeSpeeds == null) {
                relativeSpeeds = new HashMap<>();
            }*/

            System.out.println("Bin " + idx);

            for (Link link : this.network.getLinks().values()) {


                double freeSpeed = link.getFreespeed(idx * binSize);

                double linkLength = link.getLength();

                double averageTime = travelTime.getLinkTravelTime(link, idx * binSize, null, null);

                double averageSpeed = linkLength / averageTime;

                double averageSpeedToFreeSpeedRatio = averageSpeed / freeSpeed;

                DecimalFormat df = new DecimalFormat("#.#");
                //Double relativeSpeed = df.format(averageSpeedToFreeSpeedRatio);
                double relativeSpeed = Double.valueOf(df.format(averageSpeedToFreeSpeedRatio));

                System.out.println("linkId => " + link.getId() + " linkLength => " + linkLength +
                        " averageTime => " + averageTime +
                        " averageSpeed => " + averageSpeed +
                        " freeflowspeed => " + freeSpeed +
                        " relativeSpeed => " + relativeSpeed);

                /*
                1. Get the data for the relative speed
                2. If null create new map and set frequency to 1 for the idx (hour in case 3600 is used)
                3. If not null retrieve the value for the hour and increment it and put it back.
                4. put the inner map back
                 */

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

    private CategoryDataset buildModesFrequencyDataset() {

        java.util.List<Double> relativeSpeedsCategoriesList = new ArrayList<>();
        relativeSpeedsCategoriesList.addAll(relativeSpeedFrequenciesPerBin.keySet());
        Collections.sort(relativeSpeedsCategoriesList);

        int maxHour = 24;
        double[][] dataset = new double[relativeSpeedsCategoriesList.size()][maxHour];

        for (int i = 0; i < relativeSpeedsCategoriesList.size(); i++) {

            Double relativeSpeedCategory = relativeSpeedsCategoriesList.get(i);
            Map<Integer, Integer> relativeSpeedBins = relativeSpeedFrequenciesPerBin.get(relativeSpeedCategory);

            double[] relativeSpeedFrequencyPerHour = new double[maxHour];
            int index = 0;

            for (int hour = 0; hour < maxHour; hour++) {
                Integer hourFrequency = relativeSpeedBins.get(hour);
                if (hourFrequency != null) {
                    relativeSpeedFrequencyPerHour[index] = hourFrequency;
                } else {
                    relativeSpeedFrequencyPerHour[index] = 0;
                }
                index = index + 1;
            }
            dataset[i] = relativeSpeedFrequencyPerHour;
        }

        return DatasetUtilities.createCategoryDataset("Relative Speed", "", dataset);
    }

    private void drawGraph(){

        //List<Id<Link>> linkIds = new ArrayList<>(linkAvgSpeedToFreeSpeedRatios.keySet());

        /*Collections.sort(linkIds, new Comparator<Id<Link>>() {
            @Override
            public int compare(Id<Link> o1, Id<Link> o2) {
                return Integer.parseInt(o1.toString()) - Integer.parseInt(o2.toString());
            }
        });*/

        /*for(Id<Link> linkId : linkIds){
            System.out.println("[ " +linkId.toString() + ", " + linkAvgSpeedToFreeSpeedRatios.get(linkId) + " ]");
        }*/

        /*for(int i : relativeSpeedFrequenciesPerBin.keySet()){

            System.out.println("i = " + i);

            Map<Double, Integer> data = relativeSpeedFrequenciesPerBin.get(i);

            for(Double cat : data.keySet()){

                System.out.println("[" + cat + " -> " + data.get(cat) + "]");
            }
        }*/


    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) {

        String plotTitle = "Relative Speeds Histogram";
        String xaxis = "Hour";
        String yaxis = "# of events by category";
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


            legendItems.add(new LegendItem(relativeSpeedsCategoriesList.get(i).toString(), colors.get(i)));

            plot.getRenderer().setSeriesPaint(i, colors.get(i));

        }
        plot.setFixedLegendItems(legendItems);


        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {

            e.printStackTrace();
        }
    }

    /*public double getAverageLinkTravelTime(TravelTime travelTime, int noOfBins, Link link, Person person, Vehicle vehicle){

        int noOfSecondsPerHour = 3600;
        double linkSumOfTravelTimes = 0d;

        for (int idx = 0; idx < noOfBins; idx++) {

            double ttime = travelTime.getLinkTravelTime(link, idx * noOfBins, person, vehicle);

            linkSumOfTravelTimes += ttime;
        }

        return linkSumOfTravelTimes/noOfHours;
    }*/

    public void notifyIterationStarts(EventsManager eventsManager) {

    }
}
