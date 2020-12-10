package beam.analysis.physsim;

import beam.analysis.plots.GraphUtils;
import beam.sim.config.BeamConfig;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.Ellipse2D;
import java.io.BufferedWriter;
import java.io.IOException;

public class PhyssimNetworkComparisonEuclideanVsLengthAttribute {
    private final Logger log = LoggerFactory.getLogger(PhyssimNetworkComparisonEuclideanVsLengthAttribute.class);

    private final BeamConfig beamConfig;
    private final Network network;
    private final OutputDirectoryHierarchy outputDirectoryHierarchy;

    public PhyssimNetworkComparisonEuclideanVsLengthAttribute(Network network, OutputDirectoryHierarchy outputDirectoryHierarchy, BeamConfig beamConfig) {
        this.network = network;
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
        this.beamConfig = beamConfig;
    }

    /**
     * Iteration stop notification event listener
     * @param iteration the count of the current iteration
     */
    public void notifyIterationEnds(int iteration) {
        if (beamConfig.beam().outputs().writeGraphs()) {
            try {
                writeComparisonEuclideanVsLengthAttributeCsv(iteration);
                writeComparisonEuclideanVsLengthAttributePlot(iteration);
            } catch (IOException e) {
                log.error("exception occurred due to ", e);
            }
        }
    }

    /**
     * Write CSV file with comparison of euclidean and length attribute of the network
     * @param iterationNumber
     * @throws IOException
     */
    private void writeComparisonEuclideanVsLengthAttributeCsv(int iterationNumber) throws IOException {
        String pathToCsv = outputDirectoryHierarchy.getIterationFilename(
                iterationNumber,
                "EuclideanVsLengthAttribute.csv.gz"
        );

        BufferedWriter writerObservedVsSimulated = IOUtils.getBufferedWriter(pathToCsv);
        writerObservedVsSimulated.write("linkId,euclidean,length\n");

        for (Link link : network.getLinks().values()) {
            writerObservedVsSimulated.write(
                    String.format("%s,%f,%f\n",
                            link.getId().toString(),
                            CoordUtils.calcEuclideanDistance(
                                    link.getFromNode().getCoord(),
                                    link.getToNode().getCoord()
                            ), link.getLength())
            );
        }

        writerObservedVsSimulated.close();
    }

    /**
     * Make plot with comparison of euclidean and length attribute of the network
     * @param iterationNumber
     * @throws IOException
     */
    private void writeComparisonEuclideanVsLengthAttributePlot(int iterationNumber) throws IOException {
        String pathToPlot = outputDirectoryHierarchy.getIterationFilename(
                iterationNumber,
                "EuclideanVsLengthAttributePlot.png"
        );

        XYSeries series = new XYSeries("Euclidean vs Length attribute", false);

        for (Link link : network.getLinks().values()) {
            series.add(
                    CoordUtils.calcEuclideanDistance(
                            link.getFromNode().getCoord(),
                            link.getToNode().getCoord()
                    ), link.getLength());
        }

        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(series);

        JFreeChart chart = ChartFactory.createScatterPlot(
                "Euclidean vs Length attribute",
                "Euclidean",
                "Length",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        XYPlot xyplot = chart.getXYPlot();
        xyplot.setDomainCrosshairVisible(false);
        xyplot.setRangeCrosshairVisible(false);

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesShape(0, new Ellipse2D.Double(0, 0, 5, 5));
        renderer.setSeriesLinesVisible(0, false);

        xyplot.setRenderer(0, renderer);

        GraphUtils.saveJFreeChartAsPNG(
                chart,
                pathToPlot,
                1000,
                1000
        );
    }
}
