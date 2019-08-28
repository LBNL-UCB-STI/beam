package beam.matsim;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.log4j.Logger;
import org.matsim.analysis.IterationStopWatch;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.corelisteners.PlansDumping;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

@Singleton
public class CustomPlansDumpingImpl implements PlansDumping, BeforeMobsimListener {
    static final private Logger log = Logger.getLogger(CustomPlansDumpingImpl.class);

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
            final String inputCRS = config.plans().getInputCRS();
            final String internalCRS = config.global().getCoordinateSystem();

            if (inputCRS == null) {
                new PopulationWriter(population, network).write(controlerIO.getIterationFilename(event.getIteration(), "plans.xml.gz"));
            } else {
                log.info("re-projecting population from " + internalCRS + " back to " + inputCRS + " for export");

                final CoordinateTransformation transformation =
                        TransformationFactory.getCoordinateTransformation(
                                internalCRS,
                                inputCRS);

                new PopulationWriter(transformation, population, network).write(controlerIO.getIterationFilename(event.getIteration(), "plans.xml.gz"));
            }
            log.info("finished plans dump.");
            stopwatch.endOperation("dump all plans");
        }
    }

}
