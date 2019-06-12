package beam.sim.akkaguice

/**
  * Created by sfeygin on 2/6/17.
  */
/**
  * A creator for actors that allows us to return actor prototypes that are created by Guice
  * (and therefore injected with any dependencies needed by that actor). Since all untyped actors
  * implement the Actor trait, we need to use a name annotation on each actor (defined in the Guice
  * module) so that the name-based lookup obtains the correct actor from Guice.
  */
import akka.actor.{Actor, IndirectActorProducer}
import com.google.inject.Injector

private[akkaguice] class ActorProducer[A <: Actor](injector: Injector, clazz: Class[A]) extends IndirectActorProducer {
  def actorClass: Class[A] = clazz
  def produce(): A = injector.getBinding(clazz).getProvider.get()
}
