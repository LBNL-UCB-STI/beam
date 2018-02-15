package beam.analysis.plots;

import beam.agentsim.agents.RideHailSurgePricingManager;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;
import scala.collection.Iterator;
import scala.collection.mutable.ArrayBuffer;

import java.io.IOException;

public class GraphRideHailingRevenue {

    public static void createGraph(RideHailSurgePricingManager surgePricingManager){

        ArrayBuffer<Object> data = surgePricingManager.rideHailingRevenue();

        DefaultCategoryDataset dataSet = createDataset(data);
        drawRideHailingRevenueGraph(dataSet);
    }

    public static void drawRideHailingRevenueGraph(DefaultCategoryDataset dataSet) {

        JFreeChart chart = ChartFactory.createLineChart(
                "Revenue/Iteration",
                "iteration","revenue",
                dataSet,
                PlotOrientation.VERTICAL,
                true,true,false);

        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("revenuegraph.png");
        try {
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static DefaultCategoryDataset createDataset(ArrayBuffer<Object> data) {

        DefaultCategoryDataset dataset = new DefaultCategoryDataset( );

        Iterator iterator = data.iterator();
        for(int i=0; iterator.hasNext(); i++){
            Double revenue = (Double)iterator.next();
            dataset.addValue(revenue, "revenue", "" + i);
        }

        return dataset;
    }
}
