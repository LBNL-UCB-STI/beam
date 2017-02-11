package beam.metasim.akkaguice

import akka.actor.{Actor, IndirectActorProducer}
import com.google.inject.name.Names
import com.google.inject.{Injector, Key}

/**
  * Created by sfeygin on 2/6/17.
  */
/**
  * A creator for actors that allows us to return actor prototypes that are created by Guice
  * (and therefore injected with any dependencies needed by that actor). Since all untyped actors
  * implement the Actor trait, we need to use a name annotation on each actor (defined in the Guice
  * module) so that the name-based lookup obtains the correct actor from Guice.
  */
class ActorProducer(val injector: Injector, val actorName: String) extends IndirectActorProducer {

  override def actorClass: Class[Actor] = classOf[Actor]

  override def produce(): Actor =
    injector.getBinding(Key.get(classOf[Actor], Names.named(actorName))).getProvider.get()

}

