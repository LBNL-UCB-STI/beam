package beam.sim

import org.matsim.analysis._
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.EventsManagerModule
import org.matsim.core.mobsim.DefaultMobsimModule
import org.matsim.core.population.VspPlansCleanerModule
import org.matsim.core.replanning.StrategyManagerModule
import org.matsim.core.router.TripRouterModule
import org.matsim.core.router.costcalculators.TravelDisutilityModule
import org.matsim.core.scoring.ExperiencedPlansModule
import org.matsim.core.scoring.functions.CharyparNagelScoringFunctionModule
import org.matsim.core.trafficmonitoring.TravelTimeCalculatorModule
import org.matsim.counts.CountsModule
import org.matsim.pt.counts.PtCountsModule
import org.matsim.vis.snapshotwriters.SnapshotWritersModule

/**
  * A custom controller module for matsim modules.
  */
class ControllerModule extends AbstractModule {

  override def install(): Unit = {
    install(new EventsManagerModule)
    install(new DefaultMobsimModule)
    install(new TravelTimeCalculatorModule)
    install(new TravelDisutilityModule)
    install(new CharyparNagelScoringFunctionModule)
    install(new ExperiencedPlansModule)
    install(new TripRouterModule)
    install(new StrategyManagerModule)
    install(new LinkStatsModule)
    install(new VolumesAnalyzerModule)
    install(new LegHistogramModule)
    install(new LegTimesModule)
    install(new TravelDistanceStatsModule)
    install(new ScoreStatsModule)
    install(new CountsModule)
    install(new PtCountsModule)
    install(new VspPlansCleanerModule)
    install(new SnapshotWritersModule)

  }
}
