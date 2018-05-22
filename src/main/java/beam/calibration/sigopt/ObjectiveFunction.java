package beam.calibration.sigopt;

public interface ObjectiveFunction<T extends Number> {
    T getValue(DecisionVariable arg);
}
