package beam.metasim.akkaguice

import akka.actor.{Actor, ActorRef, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import com.google.inject.Injector
import java.lang.reflect.Method
import com.google.inject.assistedinject.FactoryModuleBuilder
import com.google.inject.{Binder, AbstractModule}
import scala.reflect.ClassTag
/**
  * Created by sfeygin on 2/6/17.
  */
class GuiceAkkaExtensionImpl extends Extension {

  private var injector: Injector = _

  def initialize(injector: Injector) {
    this.injector = injector
  }

  def props(actorName: String) = Props(classOf[ActorProducer], injector, actorName)

}
object GuiceAkkaExtension extends ExtensionId[GuiceAkkaExtensionImpl] with ExtensionIdProvider {

  /** Register ourselves with the ExtensionIdProvider */
  override def lookup() = GuiceAkkaExtension

  /** Called by Akka in order to create an instance of the extension. */
  override def createExtension(system: ExtendedActorSystem) = new GuiceAkkaExtensionImpl

  /** Java API: Retrieve the extension for the given system. */
  override def get(system: ActorSystem): GuiceAkkaExtensionImpl = super.get(system)

}

/**
  * A convenience trait for an actor companion object to extend to provide names.
  */
trait NamedActor {
  def name: String
}

/**
  * Mix in with Guice Modules that contain providers for top-level actor refs.
  */
trait GuiceAkkaActorRefProvider {
  def propsFor(system: ActorSystem, name: String): Props = GuiceAkkaExtension(system).props(name)
  def provideActorRef(system: ActorSystem, name: String): ActorRef = system.actorOf(propsFor(system, name))
}



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

import akka.actor._
import com.google.inject.Injector
import scala.reflect.ClassTag

trait ActorInject {
  protected def injector: Injector

  protected def injectActor[A <: Actor](implicit factory: ActorRefFactory, tag: ClassTag[A]): ActorRef =
    factory.actorOf(Props(classOf[ActorProducer], injector, tag.runtimeClass))

  protected def injectActor[A <: Actor](name: String)(implicit factory: ActorRefFactory, tag: ClassTag[A]): ActorRef =
    factory.actorOf(Props(classOf[ActorProducer], injector, tag.runtimeClass), name)

  protected def injectActor(create: => Actor)(implicit factory: ActorRefFactory): ActorRef =
    factory.actorOf(Props(create))

  protected def injectActor(create: => Actor, name: String)(implicit factory: ActorRefFactory): ActorRef =
    factory.actorOf(Props(create), name)

  protected def injectTopActor[A <: Actor](implicit tag: ClassTag[A]): ActorRef =
    injector.getInstance(classOf[ActorSystem]).actorOf(Props(classOf[ActorProducer], injector, tag.runtimeClass))

  protected def injectTopActor[A <: Actor](name: String)(implicit tag: ClassTag[A]): ActorRef =
    injector.getInstance(classOf[ActorSystem]).actorOf(Props(classOf[ActorProducer], injector, tag.runtimeClass), name)
}