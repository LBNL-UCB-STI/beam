package beam.utils.plansampling

import java.util

import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.population.Plan
import org.matsim.core.population.algorithms.PermissibleModesCalculator

import scala.collection.JavaConverters

object PermissibleModeUtils {

  class AllowAllModes extends PermissibleModesCalculator {
    override def getPermissibleModes(plan: Plan): util.Collection[String] = {
      JavaConverters.asJavaCollection(BeamMode.availableModes.map(_.toString))
    }
  }

  def permissibleModeParser(permissibleModes: String): Seq[String] = {
    permissibleModes.split(",").toSeq
  }
}
