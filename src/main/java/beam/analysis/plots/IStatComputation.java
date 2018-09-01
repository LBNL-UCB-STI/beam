package beam.analysis.plots;

public interface IStatComputation<T, R> {
    R compute(T stat);
}
