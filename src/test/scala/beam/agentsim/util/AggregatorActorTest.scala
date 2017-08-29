package beam.agentsim.util

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props}
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import beam.agentsim.agents.util.AggregatorActor
import beam.agentsim.agents.util.AggregatedRequest
import beam.agentsim.agents.util.SingleActorAggregationResult
import beam.agentsim.agents.util.MultipleAggregationResult
import org.scalatest.{FunSpec, FunSpecLike, MustMatchers}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

/**
  * Created by dserdiuk on 7/16/17.
  */
class AggregatorActorTest extends TestKit(ActorSystem("testsystem")) with MustMatchers with FunSpecLike with ImplicitSender {


  describe("aggregator actor") {

    it("should send back single result") {

      // Creation of the TestActorRef
      val aggregator = TestActorRef[AggregatorActor](Props(classOf[AggregatorActor], self, None))
      val testActorRef = system.actorOf(Props[TestActor](), "testActor1")
//      aggregator ! AggregatedRequest(Map(testActorRef -> List("Ping")))
      expectMsg(FiniteDuration(10, TimeUnit.SECONDS), SingleActorAggregationResult(List("Ping handled")))
    }
    it("send back multiple results") {
      // Creation of the TestActorRef
      val aggregator = TestActorRef[AggregatorActor](Props(classOf[AggregatorActor], self, None))
      val testActorRef = system.actorOf(Props[TestActor](),  "testActor2")
      val testActorRef2 = system.actorOf(Props[TestActor](),  "testActor3")

//      aggregator ! AggregatedRequest(Map(testActorRef -> List("Ping"), testActorRef2 -> List("Pong")))
      // This method assert that the `testActor` has received a specific message
//      expectMsg(MultipleAggregationResult(Map(testActorRef -> List("Ping handled"), testActorRef2 -> List("Pong handled"))))
    }
  }
}

class TestActor extends Actor {

  def receive: Receive = {
    case newMsg: String =>
      sender() ! newMsg + " handled"
  }
}
