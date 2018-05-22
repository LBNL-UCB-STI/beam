package beam.calibration.sigopt;

import beam.experiment.ExperimentDef;
import beam.experiment.Factor;
import beam.experiment.Header;
import beam.experiment.Level;
import com.sigopt.Sigopt;
import com.sigopt.exception.APIConnectionError;
import com.sigopt.exception.APIException;
import com.sigopt.exception.SigoptException;
import com.sigopt.model.Bounds;
import com.sigopt.model.Experiment;
import com.sigopt.model.Parameter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BeamSigoptHelperTest {

    public static final String TEST_BEAM_EXPERIMENT_LOC = "test/input/beamville/example-experiment/experiment.yml";

    private Experiment experiment = null;

    @Before
    public void setUp() throws Exception {
        if ((System.getenv("SIGOPT_DEV_ID") !=null )) {
            Sigopt.clientToken = System.getenv("SIGOPT_DEV_ID");
        }else{
            throw new APIConnectionError("Correct developer client token must be present in environment as SIGOPT_DEV_ID");
        }
    }

    @Test
    public void getExperimentDef() {
        ExperimentDef testExperimentDef = BeamSigoptHelper.getExperimentDef(TEST_BEAM_EXPERIMENT_LOC);
        assertEquals(testExperimentDef.getHeader().getTitle(), "Example-Experiment");
        assertEquals(testExperimentDef.getHeader().beamTemplateConfPath(), "test/input/beamville/beam.conf");

    }

    @Test
    public void createExperiment() {
        ExperimentDef testExperimentDef = BeamSigoptHelper.getExperimentDef(TEST_BEAM_EXPERIMENT_LOC);
        try {
            experiment = BeamSigoptHelper.createExperiment(testExperimentDef);
            System.out.println("Created experiment: https://sigopt.com/experiment/" + experiment.getId());
            final List<Parameter> expParams = experiment.getParameters();

            // First is the rideHailParams
            final Parameter rideHailParams = expParams.iterator().next();
            assertEquals(rideHailParams.getName(),"beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation");
            assertEquals(rideHailParams.getBounds().getMax(),0.1,0);
            assertEquals(rideHailParams.getBounds().getMin(), 0.001, 0.0);

            // Second is transitCapacityParams
            final Parameter transitCapacityParams = expParams.iterator().next();
            assertEquals(transitCapacityParams.getName(),"beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation");
            assertEquals(transitCapacityParams.getBounds().getMax(),0.1,0);
            assertEquals(transitCapacityParams.getBounds().getMin(), 0.001, 0.0);

        } catch (SigoptException e) {
            e.printStackTrace();
        }

    }

    @Test
    public void factorToParameter() {
        List<Level> levels = new ArrayList<>();

        HashMap<String,Object> levelHighMap = new HashMap<>();
        levelHighMap.put("beam.agentsim.tuning.transitCapacity",0.1);

        HashMap<String,Object> levelLowMap = new HashMap<>();
        levelLowMap.put("beam.agentsim.tuning.transitCapacity",0.01);

        Level levelHigh = new Level("High",levelHighMap);
        Level levelLow = new Level("Low",levelLowMap);
        levels.add(levelHigh);
        levels.add(levelLow);

        // We actually ignore the given name for the factor in favor of the
        // parameter class name.
        Factor factor = new Factor("MyFactor", levels);

        final Parameter parameter = BeamSigoptHelper.factorToParameter(factor);

        assertEquals(parameter.getName(), "beam.agentsim.tuning.transitCapacity");
        assertEquals(parameter.getType(), "double");

        Bounds bounds = parameter.getBounds();

        final Double max = bounds.getMax();
        final Double min = bounds.getMin();

        assertEquals(max, 0.1,0.0);
        assertEquals(min, 0.001, 0.0);
    }

    @After
    public void tearDown() throws SigoptException {

        if (experiment != null) {
            System.out.println("Deleting experiment: https://sigopt.com/experiment/" + experiment.getId()+" ...");
            Experiment.delete(experiment.getId()).call();
            System.out.println("Successfully deleted!");
        }else{
            throw new APIConnectionError("Experiment not found!");
        }
    }
}
