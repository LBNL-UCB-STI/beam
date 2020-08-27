package beam.router.skim.urbansim

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{AsyncWordSpecLike, BeforeAndAfterAll, Matchers}

class WorkerActorSpec extends TestKit(ActorSystem("WorkerActorSpec"))     with ImplicitSender
  with AsyncWordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "A WorkerActor" can {

  }
}