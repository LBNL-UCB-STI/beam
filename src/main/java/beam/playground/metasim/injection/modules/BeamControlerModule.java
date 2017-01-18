package beam.playground.metasim.injection.modules;

import org.matsim.analysis.LegHistogramModule;
import org.matsim.analysis.LegTimesModule;
import org.matsim.analysis.LinkStatsModule;
import org.matsim.analysis.ModeStatsModule;
import org.matsim.analysis.ScoreStatsModule;
import org.matsim.analysis.TravelDistanceStatsModule;
import org.matsim.analysis.VolumesAnalyzerModule;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.events.EventsManagerModule;
import org.matsim.core.mobsim.DefaultMobsimModule;
import org.matsim.core.population.VspPlansCleanerModule;
import org.matsim.core.replanning.StrategyManagerModule;
import org.matsim.core.router.costcalculators.TravelDisutilityModule;
import org.matsim.core.scoring.ExperiencedPlansModule;
import org.matsim.core.scoring.functions.CharyparNagelScoringFunctionModule;
import org.matsim.core.trafficmonitoring.TravelTimeCalculatorModule;
import org.matsim.counts.CountsModule;
import org.matsim.pt.counts.PtCountsModule;
import org.matsim.vis.snapshotwriters.SnapshotWritersModule;

public class BeamControlerModule extends AbstractModule {

	@Override
	public void install() {
	        install(new EventsManagerModule());
	        install(new DefaultMobsimModule());
	        install(new TravelTimeCalculatorModule());
	        install(new TravelDisutilityModule());
	        install(new CharyparNagelScoringFunctionModule());
	        install(new ExperiencedPlansModule());
	        install(new BeamTripRouterModule());
	        install(new StrategyManagerModule());
	        install(new LinkStatsModule());
	        install(new VolumesAnalyzerModule());
	        install(new LegHistogramModule());
	        install(new LegTimesModule());
	        install(new TravelDistanceStatsModule());
	        install(new ScoreStatsModule());
	        install(new ModeStatsModule());
	        install(new CountsModule());
	        install(new PtCountsModule());
	        install(new VspPlansCleanerModule());
	        install(new SnapshotWritersModule());
	}

}
