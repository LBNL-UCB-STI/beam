package beam

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import beam.metasim.playground.sid.agents.PersonAgent
import com.google.inject.Guice
import org.scalatest.{FunSpecLike, MustMatchers}

/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("beam-actor-system")) with MustMatchers with FunSpecLike with ImplicitSender  {

  describe("PersonAgent FSM"){
      it("should allow setting the current activity"){
        val injector = Guice.createInjector(new MetaSimRoutingModule())
        val testPersonAgent = TestActorRef(Props(new PersonAgent))
      }
  }

}
