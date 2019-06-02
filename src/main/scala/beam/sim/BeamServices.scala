package beam.sim

import java.io.FileNotFoundException

import akka.actor.ActorRef
import beam.agentsim.agents.choice.mode.ModeIncentive
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
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
  val rideHailTransitModes: Seq[BeamMode]
  var personHouseholds: Map[Id[Person], Household]

  def matsimServices: MatsimServices
  def tazTreeMap: TAZTreeMap
  val modeIncentives: ModeIncentive
  def networkHelper: NetworkHelper
}

class BeamServicesImpl @Inject()(val injector: Injector) extends BeamServices {

  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  val beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])
  def beamScenario: BeamScenario = injector.getInstance(classOf[BeamScenario])
  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])

  val rideHailTransitModes: Seq[BeamMode] =
    if (beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.equalsIgnoreCase("all")) BeamMode.transitModes
    else if (beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.equalsIgnoreCase("mass"))
      BeamMode.massTransitModes
    else {
      beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.toUpperCase
        .split(",")
        .map(BeamMode.fromString)
        .toSeq
        .flatten
    }

  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory = _
  var beamRouter: ActorRef = _

  var personHouseholds: Map[Id[Person], Household] = Map()

  override def matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])

  val modeIncentives = ModeIncentive(beamConfig.beam.agentsim.agents.modeIncentive.filePath)

  override def networkHelper: NetworkHelper = injector.getInstance(classOf[NetworkHelper])
  override def tazTreeMap: TAZTreeMap = injector.getInstance(classOf[TAZTreeMap])
}

object BeamServices {

  type FuelTypePrices = Map[FuelType, Double]

  private val logger = LoggerFactory.getLogger(this.getClass)

  val defaultTazTreeMap: TAZTreeMap = {
    val tazQuadTree: QuadTree[TAZ] = new QuadTree(-1, -1, 1, 1)
    val taz = new TAZ("0", new Coord(0.0, 0.0), 0.0)
    tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    new TAZTreeMap(tazQuadTree)
  }

  def getTazTreeMap(filePath: String): TAZTreeMap = {
    try {
      TAZTreeMap.fromCsv(filePath)
    } catch {
      case fe: FileNotFoundException =>
        logger.error("No TAZ file found at given file path (using defaultTazTreeMap): %s" format filePath, fe)
        defaultTazTreeMap
      case e: Exception =>
        logger.error(
          "Exception occurred while reading from CSV file from path (using defaultTazTreeMap): %s" format e.getMessage,
          e
        )
        defaultTazTreeMap
    }
  }
}
