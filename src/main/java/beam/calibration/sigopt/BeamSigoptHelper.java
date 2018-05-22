package beam.calibration.sigopt;

import beam.experiment.*;
import com.sigopt.Sigopt;
import com.sigopt.exception.SigoptException;
import com.sigopt.model.Bounds;
import com.sigopt.model.Client;
import com.sigopt.model.Experiment;
import com.sigopt.model.Parameter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class BeamSigoptHelper {

    public static ExperimentDef getExperimentDef(String beamExperimentLoc) {
        final Path absoluteBeamExperimentPath = new File(beamExperimentLoc).toPath().toAbsolutePath();
        File beamExperimentFile;
        if (!Files.exists(absoluteBeamExperimentPath)) {
            throw new IllegalArgumentException(String.format("%s file is missing", beamExperimentLoc));
        } else {
            beamExperimentFile = absoluteBeamExperimentPath.toFile();
        }

        return ExperimentGenerator.loadExperimentDefs(beamExperimentFile);
    }

    public static Experiment createExperiment(ExperimentDef beamExperiment) throws SigoptException {
        final Header header = beamExperiment.getHeader();
        final String experimentId = header.getTitle();

        final List<Factor> factors = beamExperiment.getFactors();

        final List<Parameter> parameters = factors.stream().map(BeamSigoptHelper::factorToParameter).collect(Collectors.toList());

        return Experiment.create().data(
                new Experiment.Builder()
                        .name(experimentId)
                        .parameters(parameters).build()).call();
    }

    /**
     * Converts a {@link Factor} to a SigOpt {@link Parameter}
     * assuming that there are {@link Level}s with high and low values.
     * <p>
     * The type of the parameter values is equivalent to the name of the "High"
     * {@link Level} "param".
     *
     * @param factor {@link Factor to convert}
     * @return The factor as a SigOpt {@link Parameter}
     */
    public static Parameter factorToParameter(Factor factor) {
        final List<Level> levels = factor.getLevels();
        final Double[] maxValue = new Double[1];
        final Double[] minValue = new Double[1];
        final String[] type = new String[1];

        // Get high and low levels
        levels.forEach((l) -> {
            if (l.name().equals("High")) {
                final Map.Entry<String, Object> highEntry = l.getParams().entrySet().iterator().next();
                type[0] = highEntry.getKey();
                maxValue[0] = (Double) highEntry.getValue();
            } else if (l.name().equals("Low")) {
                minValue[0] = (Double) l.getParams().values().iterator().next();
            }
        });

        return new Parameter.Builder()
                .name(type[0])
                .type("double")
                .bounds(new Bounds(minValue[0], maxValue[0])).build();

    }

    public static void CalibrationRunner(String[] args, ObjectiveFunction objectiveFunction) {

        String experimentLoc = null;
        String clientId = null;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--client_token") && i < args.length - 1) {
                clientId = args[i + 1];
            }
            if (args[i].equals("--experiments") && i < args.length - 1) {
                experimentLoc = args[i + 1];
            }
        }

        if (clientId == null) {
            throw new IllegalArgumentException("No client id supplied!");
        }

        if (experimentLoc == null) {
            throw new IllegalArgumentException("No experiment location supplied!");
        }

    }
}
