package beam.analysis.plots;

import beam.agentsim.agents.rideHail.RideHailSurgePricingManager;
import com.google.inject.Inject;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import scala.collection.Iterator;
import scala.collection.mutable.ArrayBuffer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;

import static beam.analysis.AnalysisCollector.rideHailRevenueAnalytics;

public class RideHailingRevenueAnalysis implements ControlerListener, IterationEndsListener {

    private RideHailSurgePricingManager surgePricingManager;

    private OutputDirectoryHierarchy outputDirectoryHiearchy;

    @Inject
    public RideHailingRevenueAnalysis(RideHailSurgePricingManager surgePricingManager) {
        this.surgePricingManager = surgePricingManager;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {

        outputDirectoryHiearchy = event.getServices().getControlerIO();

        // for next iteration
        surgePricingManager.updateRevenueStats();

        ArrayBuffer<?> data = surgePricingManager.rideHailingRevenue();

        createGraph(data);

        writeRideHailingRevenueCsv(data);

        rideHailRevenueAnalytics(data);
    }

    private void createGraph(ArrayBuffer<?> data) {
        DefaultCategoryDataset dataSet = createDataset(data);
        drawRideHailingRevenueGraph(dataSet);
    }

    private void drawRideHailingRevenueGraph(DefaultCategoryDataset dataSet) {

        JFreeChart chart = ChartFactory.createLineChart(
                "Ride Hail Revenue",
                "iteration", "revenue($)",
                dataSet,
                PlotOrientation.VERTICAL,
                false, true, false);

        String graphImageFile = outputDirectoryHiearchy.getOutputFilename("rideHailRevenue.png");
        try {
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            e.printStackTrace();
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

    private void writeRideHailingRevenueCsv(ArrayBuffer<?> data) {
        try {
            String fileName = outputDirectoryHiearchy.getOutputFilename("rideHailRevenue.csv");
            BufferedWriter out = new BufferedWriter(new FileWriter(new File(fileName)));

            out.write("iteration #,revenue");
            out.newLine();

            Iterator iterator = data.iterator();
            for (int i = 0; iterator.hasNext(); i++) {
                Double revenue = (Double) iterator.next();
                out.write(i + "," + revenue);
                out.newLine();
            }

            out.flush();
            out.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
