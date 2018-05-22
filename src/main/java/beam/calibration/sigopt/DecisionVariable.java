package beam.calibration.sigopt;

import com.sigopt.model.Parameter;

import java.util.ArrayList;
import java.util.List;

public interface DecisionVariable {

    public List<Parameter> params = new ArrayList<>();
}
