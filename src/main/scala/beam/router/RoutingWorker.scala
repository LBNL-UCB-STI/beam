package beam.router

import akka.actor.{Actor, ActorLogging, Props}
import beam.agentsim.agents.PersonAgent
import beam.router.BeamRouter.{RoutingRequestTripInfo, _}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

trait RoutingWorker extends Actor with ActorLogging with HasServices {
  override final def receive: Receive = {
    case InitTransit =>
      initTransit
      sender() ! TransitInited
    case RoutingRequest(requestId, params: RoutingRequestTripInfo) =>
      sender() ! calcRoute(requestId, params, getPerson(params.personId))
    case msg =>
      log.info(s"Unknown message received by Router $msg")
  }

  def calcRoute(requestId: Id[RoutingRequest], params: RoutingRequestTripInfo, person: Person): RoutingResponse

  def initTransit

  protected def getPerson(personId: Id[PersonAgent]): Person = beamServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
}

object RoutingWorker {

  trait HasProps {
    def props(beamServices: BeamServices): Props
  }

  def getRouterProps(routerClass: String, services: BeamServices): Props = {
    val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
    val module = runtimeMirror.staticModule(routerClass)
    val obj = runtimeMirror.reflectModule(module)
    val routerObject: HasProps = obj.instance.asInstanceOf[HasProps]
    routerObject.props(services)
  }
}



