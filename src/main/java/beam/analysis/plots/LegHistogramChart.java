package beam.analysis.plots;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.matsim.analysis.LegHistogram;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.utils.io.UncheckedIOException;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class LegHistogramChart {

    private static final String xAxisLabel= "time (binSize=<?> sec)";

    //We can move this method in PlotGraph class in beam-utility
    public static void writeGraphic(LegHistogram legHistogram, OutputDirectoryHierarchy CONTROLLER_IO, String fileName, final String legMode, int iteration, int binSize) {

        try {
            final XYSeriesCollection xyData = new XYSeriesCollection();
            final XYSeries departuresSerie = new XYSeries("departures", false, true);
            final XYSeries arrivalsSerie = new XYSeries("arrivals", false, true);
            final XYSeries onRouteSerie = new XYSeries("en route", false, true);
            int[] countsDep = legHistogram.getDepartures(legMode);
            int[] countsArr = legHistogram.getArrivals(legMode);
            int[] countsStuck = legHistogram.getStuck(legMode);
            int onRoute  = 0;
            for (int i = 0; i < countsDep.length; i++) {
                onRoute = onRoute + countsDep[i] - countsArr[i] - countsStuck[i];
                int hour = i* binSize / 60 / 60;
                departuresSerie.add(hour, countsDep[i]);
                arrivalsSerie.add(hour, countsArr[i]);
                onRouteSerie.add(hour, onRoute);
            }

            xyData.addSeries(departuresSerie);
            xyData.addSeries(arrivalsSerie);
            xyData.addSeries(onRouteSerie);

            final JFreeChart chart = ChartFactory.createXYLineChart(
                    "Trip Histogram, " + legMode + ", it." + iteration,
                    xAxisLabel.replace("<?>", String.valueOf(binSize)), "# persons",
                    xyData,
                    PlotOrientation.VERTICAL,
                    true,   // legend
                    false,   // tooltips
                    false   // urls
            );

            XYPlot plot = chart.getXYPlot();

            final CategoryAxis axis1 = new CategoryAxis("hour");
            axis1.setTickLabelFont(new Font("SansSerif", Font.PLAIN, 7));
            plot.setDomainAxis(new NumberAxis(xAxisLabel.replace("<?>", String.valueOf(binSize))));

            plot.getRenderer().setSeriesStroke(0, new BasicStroke(2.0f));
            plot.getRenderer().setSeriesStroke(1, new BasicStroke(2.0f));
            plot.getRenderer().setSeriesStroke(2, new BasicStroke(2.0f));
            plot.setBackgroundPaint(Color.white);
            plot.setRangeGridlinePaint(Color.gray);
            plot.setDomainGridlinePaint(Color.gray);


            fileName = fileName + "_" + legMode + ".png";
            String path = CONTROLLER_IO.getIterationFilename(iteration, fileName);
            int index = path.lastIndexOf("/");
            File outDir = new File(path.substring(0, index) + "/tripHistogram");
            if (!outDir.isDirectory()) Files.createDirectories(outDir.toPath());
            String newPath = outDir.getPath() + path.substring(index);
            ChartUtilities.saveChartAsPNG(new File(newPath), chart, 1024, 768);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
