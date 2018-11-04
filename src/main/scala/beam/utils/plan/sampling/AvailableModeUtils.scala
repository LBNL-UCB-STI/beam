package beam.utils.plan.sampling

import java.util

import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_TRANSIT, WALK, WALK_TRANSIT}
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Person, Plan}
import org.matsim.core.population.algorithms.PermissibleModesCalculator
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters

/**
  * Several utility/convenience methods for mode availability. Note that the MATSim convention
  * is to call these permissible modes. BEAM uses available modes. The semantics are identical.
  */
object AvailableModeUtils {

  class AllowAllModes extends PermissibleModesCalculator {
    override def getPermissibleModes(plan: Plan): util.Collection[String] = {
      JavaConverters.asJavaCollection(BeamMode.availableModes.map(_.toString))
    }
  }

  def availableModeParser(availableModes: String): Seq[BeamMode] = {
    availableModes.split(",").toSeq map BeamMode.withValue
  }

  def availableModesForPerson(person: Person): Seq[BeamMode] = {
    val availModes = person.getCustomAttributes
      .get("beam-attributes")
      .asInstanceOf[AttributesOfIndividual]
      .availableModes :+ WALK :+ WALK_TRANSIT :+ DRIVE_TRANSIT
    if (availModes.contains(RIDE_HAIL)) {
      availModes :+ RIDE_HAIL_TRANSIT
    } else {
      availModes
    }
  }

  def isModeAvailableForPerson[T <: BeamMode](
                                               person: Person,
                                               vehicleId: Id[Vehicle],
                                               mode: BeamMode
                                             ): Boolean = {
    AvailableModeUtils.availableModesForPerson(person).contains(mode)
  }

}
