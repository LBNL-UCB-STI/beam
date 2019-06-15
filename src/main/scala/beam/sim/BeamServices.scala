package beam.sim

import java.io.FileNotFoundException

import akka.actor.ActorRef
import beam.agentsim.agents.choice.mode.ModeIncentive
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.NetworkHelper
import com.google.inject.{ImplementedBy, Inject, Injector}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler._
import org.matsim.core.utils.collections.QuadTree
import org.matsim.households.Household
import org.slf4j.LoggerFactory

@ImplementedBy(classOf[BeamServicesImpl])
trait BeamServices {
  val injector: Injector
  val controler: ControlerI
  val beamConfig: BeamConfig
  def beamScenario: BeamScenario

  val geo: GeoUtils
  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory

  var beamRouter: ActorRef

  def matsimServices: MatsimServices
  def networkHelper: NetworkHelper
  def fareCalculator: FareCalculator
  def tollCalculator: TollCalculator
}

class BeamServicesImpl @Inject()(val injector: Injector) extends BeamServices {

  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  val beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])
  val beamScenario: BeamScenario = injector.getInstance(classOf[BeamScenario])
  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])

  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory = _
  var beamRouter: ActorRef = _

  override val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])

  override def networkHelper: NetworkHelper = injector.getInstance(classOf[NetworkHelper])
  override def fareCalculator: FareCalculator = injector.getInstance(classOf[FareCalculator])
  override def tollCalculator: TollCalculator = injector.getInstance(classOf[TollCalculator])
}
