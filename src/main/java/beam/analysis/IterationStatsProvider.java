package beam.analysis;

import java.util.Map;

public interface IterationStatsProvider {
    Map<String, Double> getSummaryStats();
}
