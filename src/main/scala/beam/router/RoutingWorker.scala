package beam.router

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.router.BeamRouter.{InitTransit, RoutingRequestTripInfo, _}
import beam.sim.{BeamServices, HasServices}
import beam.utils.ProfilingUtils
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
      System.out.println(response)
    case InitTransit =>
      val timeTaken = ProfilingUtils.timeWork {
        initTransit
      }
      log.info(s"\n\nIt took $timeTaken to init transit\n\n")
      sender() ! TransitInited(List(workerId))
    case msg =>
      log.info(s"Unknown message received by Router $msg")
  }

  def calcRoute(requestId: Id[RoutingRequest], params: RoutingRequestTripInfo): RoutingResponse

  def initTransit

}

object RoutingWorker {

  trait HasProps {
    def props(beamServices: BeamServices, fareCalculator: ActorRef, workerId: Int): Props
  }

  def getRouterProps(routerClass: String, services: BeamServices, fareCalculator: ActorRef, workerId: Int): Props = {
    val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
    val module = runtimeMirror.staticModule(routerClass)
    val obj = runtimeMirror.reflectModule(module)
    val routerObject: HasProps = obj.instance.asInstanceOf[HasProps]
    routerObject.props(services, fareCalculator, workerId)
  }
}



