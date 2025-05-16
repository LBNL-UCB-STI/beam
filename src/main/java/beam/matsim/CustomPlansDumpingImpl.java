package beam.matsim;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.analysis.IterationStopWatch;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.corelisteners.PlansDumping;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.UncheckedIOException;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Singleton
public class CustomPlansDumpingImpl implements PlansDumping, BeforeMobsimListener {
    static final private Logger log = LoggerFactory.getLogger(CustomPlansDumpingImpl.class);

    @Inject
    private Config config;
    @Inject
    private Network network;
    @Inject
    private Population population;
    @Inject
    private IterationStopWatch stopwatch;
    @Inject
    private OutputDirectoryHierarchy controlerIO;
    @Inject
    private ControlerConfigGroup controlerConfigGroup;

    private int writePlansInterval() {
        return controlerConfigGroup.getWritePlansInterval();
    }

    private int writeMoreUntilIteration() {
        return controlerConfigGroup.getWritePlansUntilIteration();
    }

    @Inject
    CustomPlansDumpingImpl() {
    }

    @Override
    public void notifyBeforeMobsim(final BeforeMobsimEvent event) {
        final boolean writingPlansAtAll = writePlansInterval() > 0;
        final boolean regularWritePlans = writePlansInterval() > 0 && (event.getIteration() > 0 && event.getIteration() % writePlansInterval() == 0);
        final boolean earlyIteration = event.getIteration() <= writeMoreUntilIteration();

        if (writingPlansAtAll && (regularWritePlans || earlyIteration)) {
            stopwatch.beginOperation("dump all plans");
            log.info("dumping plans...");

            String outputFilename = controlerIO.getIterationFilename(event.getIteration(), "plans.xml.gz");
            ensureDirectoryExists(outputFilename);

            try {
                writePlans(outputFilename);
                log.info("finished plans dump successfully.");
            } catch (Exception e) {
                log.error("Failed to write plans to {}: {}", outputFilename, e.getMessage());
                throw new RuntimeException("Failed to write plans file", e);
            } finally {
                stopwatch.endOperation("dump all plans");
            }
        }
    }

    private void dumpExperiencedPlans() {
        if (!config.planCalcScore().isWriteExperiencedPlans()) {
            log.debug("Skipping experienced plans dump - disabled in config");
            return;
        }

        stopwatch.beginOperation("dump experienced plans");
        log.info("Dumping experienced plans, using our own BEAM implementation...");

        try {
            String outputFilename = controlerIO.getOutputFilename(Controler.DefaultFiles.experiencedPlans);
            String iterationFilename = controlerIO.getIterationFilename(
                    controlerConfigGroup.getLastIteration(),
                    Controler.DefaultFiles.experiencedPlans
            );

            // Ensure output directory exists
            ensureDirectoryExists(outputFilename);

            Path fromPath = Paths.get(iterationFilename);
            Path toPath = Paths.get(outputFilename);

            if (!Files.exists(fromPath)) {
                log.error("Source experienced plans file not found: {}", iterationFilename);
                log.error("Plans were probably not generated in the final iteration");
                return;
            }

            Files.copy(fromPath, toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("Successfully copied experienced plans from {} to {}", iterationFilename, outputFilename);

        } catch (IOException e) {
            log.error("Failed to copy experienced plans file: {}", e.getMessage());
            throw new UncheckedIOException("Failed to copy experienced plans file", e);
        } catch (Exception e) {
            log.error("Error while dumping experienced plans: {}", e.getMessage());
            throw new RuntimeException("Error while dumping experienced plans", e);
        } finally {
            stopwatch.endOperation("dump experienced plans");
        }
    }

    private void writePlans(String outputFilename) {
        final String inputCRS = config.plans().getInputCRS();
        final String internalCRS = config.global().getCoordinateSystem();

        if (inputCRS == null) {
            new PopulationWriter(population, network).write(outputFilename);
        } else {
            log.info("re-projecting population from {} back to {} for export", internalCRS, inputCRS);

            final CoordinateTransformation transformation =
                    TransformationFactory.getCoordinateTransformation(
                            internalCRS,
                            inputCRS);

            new PopulationWriter(transformation, population, network).write(outputFilename);
        }
    }

    private void ensureDirectoryExists(String filename) {
        try {
            Path directory = Paths.get(filename).getParent();
            if (directory != null && !Files.exists(directory)) {
                Files.createDirectories(directory);
                log.info("Created directory: {}", directory);
            }
        } catch (IOException e) {
            log.error("Failed to create directory for {}: {}", filename, e.getMessage());
            throw new RuntimeException("Failed to create output directory", e);
        }
    }
}