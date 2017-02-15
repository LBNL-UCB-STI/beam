package beam

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import beam.metasim.sim.MetaSimServices
import beam.metasim.sim.modules.BeamAgentModule
import com.google.inject.Guice
import org.scalatest.{FunSpecLike, MustMatchers}


/**
  * Created by sfeygin on 2/7/17.
  */
class PersonAgentSpec extends TestKit(ActorSystem("beam-actor-system"))
  with MustMatchers with FunSpecLike with ImplicitSender  {

  describe("PersonAgent FSM"){
      it("should allow setting the current activity"){
        val injector = Guice.createInjector(new BeamAgentModule())
        val service = injector.getInstance(classOf[MetaSimServices])
//        val testPersonAgent = TestActorRef(Props(new PersonAgent))
      }
  }

}
