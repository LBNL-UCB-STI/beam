package beam.utils.logging;

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

public class LogAggregatorAppender extends ConsoleAppender<ILoggingEvent> {

    private List<String> allMessages = new LinkedList<>();
    private DBSCANClusterer<TextClusterable> scan;
    private int eps = 3;
    private int minPoints = 0;

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
        if (event.getMessage().equals("STOP")) {
            stop();
        } else {
            allMessages.add(event.getMessage());
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
}

