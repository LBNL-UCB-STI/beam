package beam.analysis.physsim;

import beam.sim.config.BeamConfig;
import org.apache.commons.lang.ArrayUtils;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * An analysis class that shows how the link lengths are distributed over the network.
 */
public class PhyssimNetworkLinkLengthDistribution {
    private final Logger log = LoggerFactory.getLogger(PhyssimNetworkLinkLengthDistribution.class);

    private final BeamConfig beamConfig;
    private final Network network;
    private final OutputDirectoryHierarchy outputDirectoryHierarchy;
    static final String outputFileBaseName = "physsimNetworkLinkLengthHistogram";

    public PhyssimNetworkLinkLengthDistribution(Network network, OutputDirectoryHierarchy outputDirectoryHierarchy, BeamConfig beamConfig) {
        this.network = network;
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
        this.beamConfig = beamConfig;
    }

    /**
     * Iteration stop notification event listener
     * @param iteration the count of the current iteration
     */
    public void notifyIterationEnds(int iteration) {
        if(beamConfig.beam().outputs().writeGraphs()) {
            Stream<Double> networkLinkLengths = network.getLinks().values().stream().map(Link::getLength);
            Double maxLength = network.getLinks().values().stream().map(Link::getLength).max(Comparator.comparing(Double::valueOf)).orElse(0.0);
            this.generateNetworkLinksLengthHistogramGraph(networkLinkLengths,this.outputDirectoryHierarchy.getIterationFilename(iteration,outputFileBaseName+".png"),maxLength);
        }
    }

    /**
     * A histogram graph that chart+
     * s the frequencies of CACC enabled road percentage increase observed in a simulation
     * @param networkLinkLengths data for the graph
     */
    private void generateNetworkLinksLengthHistogramGraph(Stream<Double> networkLinkLengths, String graphImageFile,Double maxLength) {
        String plotTitle = "Physsim Network Length Distribution Histogram";
        String x_axis = "Link Length (im meters)";
        String y_axis = "Count";
        int width = 1000;
        int height = 600;

        Double[] value = networkLinkLengths.toArray(Double[]::new);
        int number = 50;
        HistogramDataset dataset = new HistogramDataset();
        dataset.setType(HistogramType.FREQUENCY);
        dataset.addSeries("Links Length", ArrayUtils.toPrimitive(value),number,0.0,maxLength);

        JFreeChart chart = ChartFactory.createHistogram(
                plotTitle,
                x_axis, y_axis, dataset, PlotOrientation.VERTICAL,false,true,true);

        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }


}
