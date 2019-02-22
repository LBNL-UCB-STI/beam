package beam.utils.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.commons.math3.ml.clustering.DBSCANClusterer;
import org.apache.commons.text.similarity.LongestCommonSubsequence;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class is intended to cluster log messages and output only the cluster
 * It was implemented based on algorithms DBScan and LevenshteinDistance
 * In order to use it the following appender should be added to Logback.xml
 *     <appender name="map" class="beam.utils.logging.LogAggregatorAppender">
 *         <layout class="ch.qos.logback.classic.PatternLayout">
 *             <Pattern>
 *                 %d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n
 *             </Pattern>
 *         </layout>
 *
 *         <eps>3</eps>
 *         <minPoints>0</minPoints>
 *         <stopMessage>Ending Iteration</stopMessage>
 *     </appender>
 *
 *     <root level="debug">
 *         <appender-ref ref="map"/>
 *     </root>
 *  Parameters:
 *  - eps: the radius of cluster
 *  - minPoints: minimum number of points to consider a cluster to be created
 */
public class LogAggregatorAppender extends ConsoleAppender<ILoggingEvent> {

    // TODO: this list can lead to a huge usage of memory
    private final List<String> allMessages = new LinkedList<>();
    private DBSCANClusterer<TextClusterable> scan;
    private int eps = 3;
    private int minPoints = 0;
    private String stopMessage;

    @Override
    public void start() {
        scan = new DBSCANClusterer<>(eps, minPoints, new TextDistanceCalculator());
        super.start();
    }

    @Override
    public void stop() {
        List<TextClusterable> points = allMessages.stream()
                .map(TextClusterable::new)
                .collect(Collectors.toList());
        List<Cluster<TextClusterable>> clusters = scan.cluster(points);

        Comparator<Cluster<TextClusterable>> comparator = Comparator.comparingInt(o -> o.getPoints().size());
        clusters.sort(comparator.reversed());
        System.out.println("*** Number of clusters: [" + clusters.size() + "]");
        System.out.println("*** Number of stored messages: [" + getAllSize(clusters) + "]");

        for (Cluster<TextClusterable> cluster : clusters) {
            printCluster(cluster);
        }
        allMessages.clear();
        super.stop();
    }

    private void printCluster(Cluster<TextClusterable> cluster) {
        List<TextClusterable> points = cluster.getPoints();
        String pattern = findPattern(points);
        System.out.println();
        System.out.println("*** Clustered pattern: [" + pattern + "]");
        System.out.println("*** Repetitions: [" + points.size() + "]");
        System.out.println();
    }

    private String findPattern(List<TextClusterable> points) {
        CharSequence result = points.get(0).toString();
        for (TextClusterable element : points) {
            LongestCommonSubsequence lcs = new LongestCommonSubsequence();
            result = lcs.longestCommonSubsequence(result, element.toString());
        }
        return result.toString();
    }

    private int getAllSize(List<Cluster<TextClusterable>> clusters) {
        return clusters.stream()
                .map(textClusterableCluster -> textClusterableCluster.getPoints().size())
                .mapToInt(Integer::intValue).sum();
    }

    @Override
    protected void append(final ILoggingEvent event) {
        // TODO: workaround while stop method is not called
        if (event.getMessage().contains(stopMessage)) {
            stop();
        } else if (event.getLevel().toInt() == Level.ERROR.toInt()) {
            allMessages.add(event.getMessage());
        } else {
            super.append(event);
        }
    }


    public int getEps() {
        return eps;
    }

    public void setEps(int eps) {
        System.out.println("Setting eps:" + eps);
        this.eps = eps;
    }

    public int getMinPoints() {
        return minPoints;
    }

    public void setMinPoints(int minPoints) {
        System.out.println("Setting minPoints:" + minPoints);
        this.minPoints = minPoints;
    }

    public String getStopMessage() {
        return stopMessage;
    }

    public void setStopMessage(final String stopMessage) {
        this.stopMessage = stopMessage;
    }
}
