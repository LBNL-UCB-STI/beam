package beam.utils.scenario.urbansim.censusblock

import beam.router.Modes.BeamMode
import beam.sim.config.BeamConfig.Beam
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.population.{Leg, Person, Population}

import scala.collection.JavaConverters._
import scala.util.Random

class ScenarioAdjuster(val urbansim: Beam.Urbansim, val population: Population, val seed: Int) extends StrictLogging {
  import ScenarioAdjuster._

  private val persons: Iterable[Person] = population.getPersons.values().asScala

  def adjust(): Unit = {
    clearModesIfNeeded()
  }

  private def clearModesIfNeeded(): Unit = {
    // At first we have to clear using all modes param
    clearModes(persons, "AllModes", _ => true, urbansim.fractionOfModesToClear.allModes, seed)

    clearModes(
      persons,
      BeamMode.BIKE.value,
      leg => leg.getMode.equalsIgnoreCase(BeamMode.BIKE.value),
      urbansim.fractionOfModesToClear.bike,
      seed
    )
    clearModes(
      persons,
      BeamMode.CAR.value,
      leg => leg.getMode.equalsIgnoreCase(BeamMode.CAR.value),
      urbansim.fractionOfModesToClear.car,
      seed
    )
    clearModes(
      persons,
      BeamMode.DRIVE_TRANSIT.value,
      leg => leg.getMode.equalsIgnoreCase(BeamMode.DRIVE_TRANSIT.value),
      urbansim.fractionOfModesToClear.drive_transit,
      seed
    )
    clearModes(
      persons,
      BeamMode.WALK.value,
      leg => leg.getMode.equalsIgnoreCase(BeamMode.WALK.value),
      urbansim.fractionOfModesToClear.walk,
      seed
    )
    clearModes(
      persons,
      BeamMode.WALK_TRANSIT.value,
      leg => leg.getMode.equalsIgnoreCase(BeamMode.WALK_TRANSIT.value),
      urbansim.fractionOfModesToClear.walk_transit,
      seed
    )
  }
}

object ScenarioAdjuster extends StrictLogging {

  private[censusblock] def clearModes(
    persons: Iterable[Person],
    mode: String,
    predicate: Leg => Boolean,
    fractionToClear: Double,
    seed: Int
  ): Unit = {
    if (!fractionToClear.equals(0d)) {
      val allLegsWithMode = persons.flatMap { person: Person =>
        val legs = person.getSelectedPlan.getPlanElements.asScala.collect {
          case leg: Leg if predicate(leg) => leg
        }
        legs
      }
      val totalSize = allLegsWithMode.size
      val clearModes = totalSize * fractionToClear
      logger.info(
        s"$mode, total number of legs $totalSize, need to clear modes for $clearModes as int ${clearModes.toInt} legs"
      )
      if (clearModes >= 1.0) {
        new Random(seed).shuffle(allLegsWithMode).take(clearModes.toInt).foreach { leg =>
          leg.setMode("")
        }
      }
    }
  }
}
