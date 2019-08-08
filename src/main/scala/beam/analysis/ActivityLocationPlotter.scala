package beam.analysis
import java.awt.Color
import java.util.{Observable, Observer}

import beam.sim.BeamConfigChangesObservable
import beam.sim.config.BeamConfig
import beam.utils.{PointToPlot, SpatialPlot}
import javax.inject.Inject
import org.matsim.api.core.v01.{Coord, Scenario}
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.IterationStartsEvent
import org.matsim.core.controler.listener.IterationStartsListener

class ActivityLocationPlotter @Inject()(
  config: BeamConfig,
  scenario: Scenario,
  controlerIO: OutputDirectoryHierarchy,
  beamConfigChangesObservable: BeamConfigChangesObservable
) extends IterationStartsListener
    with Observer {
  beamConfigChangesObservable.addObserver(this)
  private var beamConfig = config

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    if (beamConfig.beam.outputs.writeGraphs) {
      val activityLocationsSpatialPlot = new SpatialPlot(1100, 1100, 50)
      scenario.getPopulation.getPersons
        .values()
        .forEach(
          x =>
            x.getSelectedPlan.getPlanElements.forEach {
              case z: Activity =>
                activityLocationsSpatialPlot.addPoint(PointToPlot(z.getCoord, Color.RED, 10))
              case _ =>
          }
        )
      scenario.getPopulation.getPersons
        .values()
        .forEach(
          x => {
            val personInitialLocation: Coord =
              x.getSelectedPlan.getPlanElements
                .iterator()
                .next()
                .asInstanceOf[Activity]
                .getCoord
            activityLocationsSpatialPlot
              .addPoint(PointToPlot(personInitialLocation, Color.BLUE, 10))
          }
        )
      activityLocationsSpatialPlot.writeImage(
        controlerIO.getIterationFilename(event.getIteration, "activityLocations.png")
      )
    }
  }

  override def update(observable: Observable, o: Any): Unit = {
    val (_, updatedBeamConfig) = o.asInstanceOf[(_, BeamConfig)]
    beamConfig = updatedBeamConfig
  }
}
