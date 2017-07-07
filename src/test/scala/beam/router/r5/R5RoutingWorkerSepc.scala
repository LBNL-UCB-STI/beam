package beam.router.r5

import akka.actor.ActorSystem
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by zeesh on 7/3/2017.
  */
class R5RoutingWorkerSepc extends TestKit(ActorSystem("router-test")) with WordSpecLike
  with ImplicitSender with DefaultTimeout with Matchers with BeforeAndAfterAll {

}
