package beam.router

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.router.BeamRouter.{HasProps, InitializeRouter}
import beam.sim.BeamServices
import com.google.inject.{Inject, Provider}
import glokka.Registry.{FoundOrCreated, Register}

import scala.concurrent.Await

/**
  * Created by sfeygin on 2/7/17.
  */

object BeamRouterProvider {
}

class BeamRouterProvider @Inject()(services: BeamServices) extends Provider[ActorRef] {
  // For "ask" timeout
  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)

  override def get(): ActorRef = {
    val props = getRouterProps(services.beamConfig.beam.routing.routerClass)
    val future = services.registry ? Register("beam-router", props)
    val result = Await.result(future, timeout.duration).asInstanceOf[AnyRef]
    val routerRef = result.asInstanceOf[FoundOrCreated].ref

    Await.result(routerRef ? InitializeRouter, timeout.duration)
    routerRef
  }

  def getRouterProps(routerClass: String): Props = {
    val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
    val module = runtimeMirror.staticModule(routerClass)
    val obj = runtimeMirror.reflectModule(module)
    val routerObject:HasProps = obj.instance.asInstanceOf[HasProps]
    routerObject.props(services)
  }
}
