package beam.analysis.plots;

public interface StatsComputation<T, R> {
    R compute(T stat);
}
