package beam.router

import akka.actor.{Actor, ActorLogging, Props}
import beam.router.BeamRouter.{RoutingRequestTripInfo, _}
import beam.router.gtfs.FareCalculator
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id

trait RoutingWorker extends Actor with ActorLogging with HasServices {

  def workerId: Int

  override final def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing RoutingWorker")
      context.parent ! RouterInitialized
      sender() ! RouterInitialized
    case RoutingRequest(requestId, params: RoutingRequestTripInfo) =>
      val response = calcRoute(requestId, params)
      sender() ! response
    case msg =>
      log.info(s"Unknown message received by Router $msg")
  }

  def calcRoute(requestId: Id[RoutingRequest], params: RoutingRequestTripInfo): RoutingResponse

}

object RoutingWorker {

  trait HasProps {
    def props(beamServices: BeamServices, fareCalculator: FareCalculator, workerId: Int): Props
  }

  def getRouterProps(routerClass: String, services: BeamServices, fareCalculator: FareCalculator, workerId: Int): Props = {
    val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
    val module = runtimeMirror.staticModule(routerClass)
    val obj = runtimeMirror.reflectModule(module)
    val routerObject: HasProps = obj.instance.asInstanceOf[HasProps]
    routerObject.props(services, fareCalculator, workerId)
  }
}



