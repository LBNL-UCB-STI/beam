package beam.calibration.sigopt;

import beam.experiment.ExperimentDef;
import beam.experiment.ExperimentGenerator;
import beam.experiment.Header;
import com.sigopt.Sigopt;

import com.sigopt.exception.SigoptException;
import com.sigopt.model.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class BeamSigoptTuner {

    private final ExperimentDef experimentDef;

    Experiment experiment;


    public BeamSigoptTuner(String beamExperimentLoc, String clientId) {
        Sigopt.clientToken = clientId;
        this.experimentDef = BeamSigoptHelper.getExperimentDef(beamExperimentLoc);

        try {
            experiment = createOrFetchExperiment();
        } catch (SigoptException e) {
            e.printStackTrace();
        }
    }


    public void createObservation(ObjectiveFunction objectiveFunction) throws SigoptException {
        final Suggestion suggestion = experiment.suggestions().create().call();
        final Assignments assignments = suggestion.getAssignments();


    }



    /**
     * Creates a new Sigopt {@link Experiment} based on the {@link ExperimentDef} model or else
     * fetches it from the online database.
     *
     * @return The fully instantiated {@link Experiment}.
     * @throws SigoptException If the experiment cannot be created, this exception is thrown.
     */
    public Experiment createOrFetchExperiment() throws SigoptException {
        Client client = new Client(Sigopt.clientToken);

        final Header header = experimentDef.getHeader();
        final String experimentId = header.getTitle();

        final Optional<Experiment> optExperiment = client.experiments().list().call().getData()
                .stream().filter(experiment ->
                        experiment.getId().equals(experimentId)).findFirst();

        return optExperiment.orElse(BeamSigoptHelper.createExperiment(experimentDef));
    }



}
