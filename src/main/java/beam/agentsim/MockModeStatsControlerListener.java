package beam.agentsim;

import org.matsim.analysis.ModeStatsControlerListener;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.TripRouter;

import javax.inject.Inject;
import javax.inject.Provider;


//public class MockModeStatsControlerListener extends ModeStatsControlerListener {
//
//    @Inject
//    MockModeStatsControlerListener(ControlerConfigGroup controlerConfigGroup, Population population1, OutputDirectoryHierarchy controlerIO,
//                                   PlanCalcScoreConfigGroup scoreConfig, Provider<TripRouter> tripRouterFactory ) {
//        super(controlerConfigGroup,population1,controlerIO,scoreConfig,tripRouterFactory);
//
//    }
//
//}
