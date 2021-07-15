package beam.sim

import akka.actor.ActorRef
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.api.BeamCustomizationAPI
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.BikeLanesAdjustment
import beam.router.skim.Skims
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.metrics.SimulationMetricCollector
import beam.utils.NetworkHelper
import com.google.inject.{ImplementedBy, Inject, Injector}
import org.matsim.core.controler._

/**
  * MATSim heavily uses Guice-based dependency injection (DI),
  * and BEAM somewhat uses it, too.
  *
  * This class is a device used by BEAM classes that can't passively use DI,
  * but still need to access things that are only available via the Injector.
  *
  * If there are any vars here, we're still in the process of refactoring them away.
  * Please don't add any.
  *
  * Actually, you probably don't need to add anything:
  *
  * If you want to introduce a new simulation-wide data container and you're wondering
  * where to put it, look no further than [[BeamScenario]].
  *
  * If you want to introduce a new simulated entity (which is, of course, restarted every
  * iteration), you want to write a [[beam.agentsim.agents.BeamAgent]] and
  * start it from [[BeamMobsimIteration]] (or from another [[beam.agentsim.agents.BeamAgent]]).
  *
  * If you want to introduce a new thing that keeps track of something iteration-to-iteration,
  * like the [[beam.agentsim.agents.ridehail.RideHailIterationHistory]],
  * please bind it into the [[Injector]] (see that class for how this is done), and let it listen
  * to events. If you want simulated entities to access that thing to look something up,
  * pass it through [[BeamMobsim]] into [[BeamMobsimIteration]], and further into your simulated entity,
  * but take care to not violate the actor model, i.e. the simulated entity must only look at a part of your class
  * that is immutable during the iteration (e.g. put together after each iteration). If that is not
  * possible, maybe the thing you want to write is really a simulated entity, in which case see above.
  *
  * For now, use MATSim constructs like [[org.matsim.core.events.handler.EventHandler]] and
  * [[org.matsim.core.controler.listener.ControlerListener]] to let your thing observe the simulation.
  *
  * Note that the bit about the [[Injector]] is totally tangential, it's just the currently most
  * consistent place to put this kind of thing. Nothing keeps us from getting rid of that construct, if
  * so desired, but we would need our own replacement for [[Controler]], and refactor [[BeamSim]] and
  * [[BeamMobsim]]. Please don't create a global variable because you don't like the [[Injector]].
  *
  * IMPORTANT: The code below is not typical code. It is the most atypical code. It is the implementation
  * of a refactoring device. Real code never needs to directly reference the injector in any way, except
  * in the outermost layer, the main method basically. [[BeamHelper]] in our case.
  * If you see a reference to an injector in user code, please try to remove it.
  */
@ImplementedBy(classOf[BeamServicesImpl])
trait BeamServices {
  val injector: Injector
  val controler: ControlerI
  def beamConfig: BeamConfig
  def beamScenario: BeamScenario

  val geo: GeoUtils
  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory

  var beamRouter: ActorRef
  var eventBuilderActor: ActorRef

  def matsimServices: MatsimServices
  def networkHelper: NetworkHelper
  def fareCalculator: FareCalculator
  def tollCalculator: TollCalculator
  def skims: Skims

  def simMetricCollector: SimulationMetricCollector
  def bikeLanesAdjustment: BikeLanesAdjustment

  def beamCustomizationAPI: BeamCustomizationAPI
}

class BeamServicesImpl @Inject() (val injector: Injector) extends BeamServices {
  val controler: ControlerI = injector.getInstance(classOf[ControlerI])

  override val bikeLanesAdjustment: BikeLanesAdjustment = injector.getInstance(classOf[BikeLanesAdjustment])

  private val beamConfigChangesObservable: BeamConfigChangesObservable =
    injector.getInstance(classOf[BeamConfigChangesObservable])

  def beamConfig: BeamConfig = beamConfigChangesObservable.lastBeamConfig

  val beamScenario: BeamScenario = injector.getInstance(classOf[BeamScenario])
  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])

  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory = _
  var beamRouter: ActorRef = _
  var eventBuilderActor: ActorRef = _

  override val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])

  override lazy val networkHelper: NetworkHelper = injector.getInstance(classOf[NetworkHelper])
  override lazy val fareCalculator: FareCalculator = injector.getInstance(classOf[FareCalculator])
  override lazy val tollCalculator: TollCalculator = injector.getInstance(classOf[TollCalculator])
  override lazy val skims: Skims = injector.getInstance(classOf[Skims])

  override lazy val simMetricCollector: SimulationMetricCollector =
    injector.getInstance(classOf[SimulationMetricCollector])

  override def beamCustomizationAPI: BeamCustomizationAPI = injector.getInstance(classOf[BeamCustomizationAPI])
}
