package beam.analysis.plots;

import beam.agentsim.agents.ridehail.RideHailSurgePricingManager;
import beam.analysis.plots.modality.RideHailDistanceRowModel;
import com.google.inject.Inject;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.Iterator;
import scala.collection.mutable.ArrayBuffer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static beam.analysis.AnalysisCollector.rideHailRevenueAnalytics;

public class RideHailRevenueAnalysis implements ControlerListener, IterationEndsListener {
    private final Logger log = LoggerFactory.getLogger(RideHailRevenueAnalysis.class);

    private final RideHailSurgePricingManager surgePricingManager;
    static final String fileBaseName = "rideHailRevenue";

    private OutputDirectoryHierarchy outputDirectoryHiearchy;

    @Inject
    public RideHailRevenueAnalysis(RideHailSurgePricingManager surgePricingManager) {
        this.surgePricingManager = surgePricingManager;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {

        outputDirectoryHiearchy = event.getServices().getControlerIO();

        // for next iteration
        surgePricingManager.updateRevenueStats();

        ArrayBuffer<?> data = surgePricingManager.rideHailRevenue();

        RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.get(event.getIteration());
        if (model == null)
            model = new RideHailDistanceRowModel();
        model.setMaxSurgePricingLevel(surgePricingManager.maxSurgePricingLevel());
        model.setSurgePricingLevelCount(surgePricingManager.surgePricingLevelCount());
        model.setTotalSurgePricingLevel(surgePricingManager.totalSurgePricingLevel());
        GraphUtils.RIDE_HAIL_REVENUE_MAP.put(event.getIteration(), model);
        if(surgePricingManager.beamServices().beamConfig().beam().outputs().writeGraphs()){
            createGraph(data);
        }

        writeRideHailRevenueCsv(data);

        rideHailRevenueAnalytics(data);
    }

    private void createGraph(ArrayBuffer<?> data) {
        DefaultCategoryDataset dataSet = createDataset(data);
        drawRideHailRevenueGraph(dataSet);
    }

    private void drawRideHailRevenueGraph(DefaultCategoryDataset dataSet) {
        JFreeChart chart = GraphUtils.createLineChartWithDefaultSettings(
                dataSet,
                "Ride Hail Revenue",
                "iteration", "revenue($)",
                false, true
        );

        String graphImageFile = outputDirectoryHiearchy.getOutputFilename(fileBaseName + ".png");
        try {
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private DefaultCategoryDataset createDataset(ArrayBuffer<?> data) {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        Iterator iterator = data.iterator();
        for (int i = 0; iterator.hasNext(); i++) {
            Double revenue = (Double) iterator.next();
            dataset.addValue(revenue, "revenue", "" + i);
        }

        return dataset;
    }

    private void writeRideHailRevenueCsv(ArrayBuffer<?> data) {
        try {
            String fileName = outputDirectoryHiearchy.getOutputFilename(fileBaseName + ".csv");
            BufferedWriter outWriter = new BufferedWriter(new FileWriter(new File(fileName)));

            outWriter.write("iteration #,revenue");
            outWriter.newLine();

            Iterator iterator = data.iterator();
            for (int i = 0; iterator.hasNext(); i++) {
                Double revenue = (Double) iterator.next();
                RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.get(i);
                if (model == null)
                    model = new RideHailDistanceRowModel();
                model.setRideHailRevenue(revenue);
                GraphUtils.RIDE_HAIL_REVENUE_MAP.put(i, model); //this map is used in RideHailStats.java
                outWriter.write(i + "," + revenue);
                outWriter.newLine();
            }

            outWriter.flush();
            outWriter.close();

        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

}
