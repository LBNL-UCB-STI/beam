package beam.analysis.plots;

import beam.sim.config.BeamConfig;
import beam.sim.metrics.MetricsSupport;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.io.UncheckedIOException;
import org.matsim.core.utils.misc.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class PersonVehicleTransitionStats implements IGraphStats, MetricsSupport {

    private static final List<String> vehicleType = new ArrayList<>(Arrays.asList("body", "rideHail","car", "others"));

    private static Map<String, TreeMap<Integer, Integer>> personEnterCount = new HashMap<>();
    private static Map<String, TreeMap<Integer, Integer>> personExitCount = new HashMap<>();
    private static Map<String, TreeMap<Integer, Integer>> onRoutes = new HashMap();
    private static Map<String, Integer> modePerson = new HashMap<>();
    private static final String fileName = "tripHistogram";
    private static final String xAxisLabel = "time (binSize=<?> sec)";
    private int binSize;
    private int numOfBins;
    private Logger log = LoggerFactory.getLogger(this.getClass());

    PersonVehicleTransitionStats(BeamConfig beamConfig){
        binSize = beamConfig.beam().outputs().stats().binSize();
        String endTime = beamConfig.matsim().modules().qsim().endTime();
        Double _endTime = Time.parseTime(endTime);
        Double _numOfTimeBins = _endTime / binSize;
        _numOfTimeBins = Math.floor(_numOfTimeBins);
        numOfBins = _numOfTimeBins.intValue() + 1;
    }


    @Override
    public void processStats(Event event) {
        processPersonVehicleTransition(event);
    }

    @Override
    public void resetStats() {
        personExitCount = new HashMap<>();
        personEnterCount = new HashMap<>();
        onRoutes = new HashMap<>();
        modePerson.clear();
    }

    @Override
    public void createGraph(IterationEndsEvent event) {
        for (String mode : onRoutes.keySet()) {
            if (personEnterCount.size() == 0 && personExitCount.size() == 0) {
                continue;
            }

            writeGraphic(event.getIteration(), mode);
        }
    }


    @Override
    public void createGraph(IterationEndsEvent event, String graphType) {

    }


    private void processPersonVehicleTransition(Event event) {
        int index = getBinIndex(event.getTime());
        if (event.getEventType() == PersonEntersVehicleEvent.EVENT_TYPE) {

            String personId = event.getAttributes().get(PersonEntersVehicleEvent.ATTRIBUTE_PERSON);
            if (personId.toLowerCase().contains("agent")) {
                return;
            }
            String vehicleId = event.getAttributes().get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE);
            if (vehicleId.contains(":")) {
                String v = vehicleId.split(":")[0];
                if (!vehicleType.contains(v)) {
                    vehicleType.add(v);
                }
            }

            String unitVehicle;
            try {
                Integer.parseInt(vehicleId);
                unitVehicle = "car";
            }
            catch (NumberFormatException e){
                unitVehicle = vehicleType.stream().filter(vehicle -> vehicleId.contains(vehicle)).findAny().orElse("others");
            }

            Integer count = modePerson.get(unitVehicle);
            if (count == null) {
                count = 1;
            } else {
                count++;
            }
            modePerson.put(unitVehicle, count);

            TreeMap<Integer, Integer> indexCount = onRoutes.get(unitVehicle);
            if (indexCount == null) {
                indexCount = new TreeMap<>();
            }
            indexCount.put(index, count);
            onRoutes.put(unitVehicle, indexCount);

            TreeMap<Integer, Integer> personEnter = personEnterCount.get(unitVehicle);
            if (personEnter == null) {
                personEnter = new TreeMap<>();
                personEnter.put(index, 1);
            } else {
                Integer numOfPerson = personEnter.get(index);
                if (numOfPerson != null) {
                    numOfPerson++;
                } else {
                    numOfPerson = 1;
                }
                personEnter.put(index, numOfPerson);
            }
            personEnterCount.put(unitVehicle, personEnter);
        }


        if (event.getEventType() == PersonLeavesVehicleEvent.EVENT_TYPE) {

            String personId = event.getAttributes().get(PersonLeavesVehicleEvent.ATTRIBUTE_PERSON);
            String vehicleId = event.getAttributes().get(PersonLeavesVehicleEvent.ATTRIBUTE_VEHICLE);


            if (personId.toLowerCase().contains("agent")) {
                return;
            }

            String unitVehicle = vehicleType.stream().filter(vehicle -> vehicleId.contains(vehicle)).findAny().orElse("others");

            Integer count = modePerson.get(unitVehicle);
            if (count != null) {
                count--;
            }
            modePerson.put(unitVehicle, count);

            TreeMap<Integer, Integer> indexCount = onRoutes.get(unitVehicle);
            if (indexCount != null) {
                indexCount.put(index, count);
            }
            onRoutes.put(unitVehicle, indexCount);


            TreeMap<Integer, Integer> personExit = personExitCount.get(unitVehicle);
            if (personExit == null) {
                personExit = new TreeMap<>();
                personExit.put(index, 1);
            } else {
                Integer numOfPerson = personExit.get(index);
                if (numOfPerson != null) {
                    numOfPerson++;
                } else {
                    numOfPerson = 1;
                }
                personExit.put(index, numOfPerson);
            }
            personExitCount.put(unitVehicle, personExit);

        }

    }

    JFreeChart getGraphic(String mode, int iteration) {

        final XYSeriesCollection xyData = new XYSeriesCollection();
        final XYSeries enterSeries = new XYSeries("Enter", false, true);
        final XYSeries exitSeries = new XYSeries("Leave", false, true);
        final XYSeries onRouteSeries = new XYSeries("on route", false, true);

        Map<Integer, Integer> personEnter = personEnterCount.get(mode);
        if (personEnter != null && personEnter.size() > 0) {
            Set<Integer> enterKeys = personEnter.keySet();
            for (Integer key : enterKeys) {
                enterSeries.add(key, personEnter.get(key));
            }
        }


        Map<Integer, Integer> personExit = personExitCount.get(mode);
        if (personExit != null && personExit.size() > 0) {
            Set<Integer> exitKeys = personExit.keySet();
            for (Integer key : exitKeys) {
                exitSeries.add(key, personExit.get(key));
            }
        }


        Map<Integer, Integer> indexCount = onRoutes.get(mode);
        if (indexCount != null && indexCount.size() > 0) {
            Set<Integer> indexKeys = indexCount.keySet();
            for (Integer key : indexKeys) {
                onRouteSeries.add(key, indexCount.get(key));
            }
        }

        xyData.addSeries(enterSeries);
        xyData.addSeries(exitSeries);
        xyData.addSeries(onRouteSeries);

        final JFreeChart chart = ChartFactory.createXYLineChart(
                "Trip Histogram, " + mode + ", it." + iteration,
                xAxisLabel.replace("<?>", String.valueOf(binSize)), "# persons",
                xyData,
                PlotOrientation.VERTICAL,
                true,   // legend
                false,   // tooltips
                false   // urls
        );


        XYPlot plot = chart.getXYPlot();
        plot.getRangeAxis().setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        final CategoryAxis axis1 = new CategoryAxis("sec");
        axis1.setTickLabelFont(new Font("SansSerif", Font.PLAIN, 7));
        plot.setDomainAxis(new NumberAxis(xAxisLabel.replace("<?>",String.valueOf(binSize))));

        plot.getRenderer().setSeriesStroke(0, new BasicStroke(2.0f));
        plot.getRenderer().setSeriesStroke(1, new BasicStroke(2.0f));
        plot.getRenderer().setSeriesStroke(2, new BasicStroke(2.0f));
        plot.setBackgroundPaint(Color.white);
        plot.setRangeGridlinePaint(Color.gray);
        plot.setDomainGridlinePaint(Color.gray);

        return chart;
    }

    public void writeGraphic(Integer iteration, String mode) {
        try {

            String filename = fileName + "_" + mode + ".png";
            String path = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iteration, filename);
            int index = path.lastIndexOf("/");
            File outDir = new File(path.substring(0, index) + "/tripHistogram");
            if (!outDir.exists()) outDir.mkdirs();
            String newPath = outDir.getPath() + path.substring(index);
            ChartUtilities.saveChartAsPNG(new File(newPath), getGraphic(mode, iteration), 1024, 768);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int getBinIndex(final double time) {
        int bin = (int) (time / this.binSize);
        if (bin >= this.numOfBins) {
            return this.numOfBins;
        }
        return bin;
    }
}