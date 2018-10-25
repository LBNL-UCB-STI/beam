package beam.analysis.plots;

import java.util.Map;

public interface IterationStats {
    Map<String, Double> getIterationSummaryStats();
}
