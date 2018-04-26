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

public class GraphRideHailingRevenue implements ControlerListener, IterationEndsListener {

    RideHailSurgePricingManager surgePricingManager;

    OutputDirectoryHierarchy outputDirectoryHiearchy;

    @Inject
    public GraphRideHailingRevenue(RideHailSurgePricingManager surgePricingManager){
        this.surgePricingManager = surgePricingManager;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {

        this.outputDirectoryHiearchy = event.getServices().getControlerIO();

        this.createGraph();

        // for next iteration
        this.surgePricingManager.updateRevenueStats();
    }


    public void createGraph(){



        ArrayBuffer<Object> data = surgePricingManager.rideHailingRevenue();

        DefaultCategoryDataset dataSet = createDataset(data);
        drawRideHailingRevenueGraph(dataSet);

        writeRideHailingRevenueCsv(data);
    }

    public void drawRideHailingRevenueGraph(DefaultCategoryDataset dataSet) {

        JFreeChart chart = ChartFactory.createLineChart(
                "Ride Hail Revenue",
                "iteration","revenue($)",
                dataSet,
                PlotOrientation.VERTICAL,
                false,true,false);

        String graphImageFile = outputDirectoryHiearchy.getOutputFilename("rideHailRevenue.png");
        try {
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private DefaultCategoryDataset createDataset(ArrayBuffer<Object> data) {

        DefaultCategoryDataset dataset = new DefaultCategoryDataset( );

        Iterator iterator = data.iterator();
        for(int i=0; iterator.hasNext(); i++){
            Double revenue = (Double)iterator.next();
            dataset.addValue(revenue, "revenue", "" + i);
        }

        return dataset;
    }

    private void writeRideHailingRevenueCsv(ArrayBuffer<Object> data) {


        try {
            String fileName = outputDirectoryHiearchy.getOutputFilename("rideHailRevenue.csv");
            BufferedWriter out = new BufferedWriter(new FileWriter(new File(fileName)));

            out.write("iteration #,revenue");
            out.newLine();

            Iterator iterator = data.iterator();
            for(int i=0; iterator.hasNext(); i++){
                Double revenue = (Double)iterator.next();
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
