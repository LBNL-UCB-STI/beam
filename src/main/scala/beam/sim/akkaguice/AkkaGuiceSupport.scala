package beam.sim.akkaguice

import java.lang.reflect.Method

import akka.actor.Actor
import com.google.inject.assistedinject.FactoryModuleBuilder
import com.google.inject.{AbstractModule, Binder}

import scala.reflect.ClassTag

/**
  *
  * @author sfeygin on 2/11/17
  */
trait AkkaGuiceSupport {
  self: AbstractModule =>

  private def accessBinder: Binder = {
    val method: Method = classOf[AbstractModule].getDeclaredMethod("binder")
    if (!method.isAccessible) {
      method.setAccessible(true)
    }
    method.invoke(this).asInstanceOf[Binder]
  }

  /** Bind an actor factory.
    *
    * This is useful for when you want to have child actors injected, and want to pass parameters into them, as well as
    * have Guice provide some of the parameters.  It is intended to be used with Guice's AssistedInject feature.
    *
    * Let's say you have an actor that looks like this:
    *
    * {{{
    * class MyChildActor @Inject() (db: Database, @Assisted id: String) extends Actor {
    *  ...
    * }
    * }}}
    *
    * So `db` should be injected, while `id` should be passed.  Now, define a trait that takes the id, and returns
    * the actor:
    *
    * {{{
    * trait MyChildActorFactory {
    *  def apply(id: String): Actor
    * }
    * }}}
    *
    * Now you can use this method to bind the child actor in your module:
    *
    * {{{
    *  class MyModule extends AbstractModule with AkkaGuiceSupport {
    *    def configure = {
    *      bindActorFactory[MyChildActor, MyChildActorFactory]
    *    }
    *  }
    * }}}
    *
    * Now, when you want an actor to instantiate this as a child actor, inject `MyChildActorFactory`:
    *
    * {{{
    * class MyActor @Inject() (myChildActorFactory: MyChildActorFactory) extends Actor with ActorInject {
    *
    *  def receive {
    *    case CreateChildActor(id) =>
    *      val child: ActorRef = injectActor(myChildActoryFactory(id))
    *      sender() ! child
    *  }
    * }
    * }}}
    *
    * @tparam ActorClass The class that implements the actor that the factory creates
    * @tparam FactoryClass The class of the actor factory */
  def bindActorFactory[ActorClass <: Actor: ClassTag, FactoryClass: ClassTag](): Unit = {
    accessBinder.install(new FactoryModuleBuilder()
      .implement(classOf[Actor], implicitly[ClassTag[ActorClass]].runtimeClass.asInstanceOf[Class[_ <: Actor]])
      .build(implicitly[ClassTag[FactoryClass]].runtimeClass))
  }
}
