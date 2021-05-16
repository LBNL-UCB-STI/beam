package beam.cosim.helics

import beam.sim.BeamHelper
import com.java.helics.helics
import org.scalatest.BeforeAndAfterAll
import beam.cosim.helics.BeamHelicsInterface._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

class BeamHelicsInterfaceSpec extends AnyFlatSpec with Matchers with BeamHelper with BeforeAndAfterAll {
  override def beforeAll(): Unit = loadHelicsIfNotAlreadyLoaded

  override def afterAll(): Unit = {
    helics.helicsCleanupLibrary()
    helics.helicsCloseLibrary()
  }

  "Running a broker and two federates" must "result is message being transmitted back and forth" in {
    lazy val beamBroker =
      getBroker(
        "Broker",
        2,
        "Federate1",
        "zmq",
        "--federates=1",
        1.0,
        1,
        1000,
        Some("LIST_MAP_ANY"),
        Some("Federate2/LIST_ANY")
      )
    lazy val beamFederate =
      getFederate(
        "Federate2",
        "zmq",
        "--federates=1 --broker_address=tcp://127.0.0.1",
        1.0,
        1,
        1000,
        Some("LIST_ANY"),
        Some("Federate1/LIST_MAP_ANY")
      )
    val f1 = Future { broker(beamBroker) }
    val f2 = Future { federate(beamFederate) }
    val aggregatedFuture = for {
      brokerRes   <- f1
      federateRes <- f2
    } yield (brokerRes, federateRes)
    try {
      Await.result(aggregatedFuture, 5.minutes)
    } catch {
      case _: TimeoutException =>
        fail("something went wrong with the co-simulation")
    }
  }

  private def federate(beamFederate: BeamFederate): Unit = {
    val message = List("foo", 123456)
    beamFederate.publish(message)
    val time1 = beamFederate.sync(1)
    time1 should be(1.0)

    val (time2, response) = (beamFederate.sync(2), beamFederate.collectJSON())
    time2 should be(2.0)
    response.size should be(1)
    response.head should contain("key"   -> "foo")
    response.head should contain("value" -> 123456)

    beamFederate.close()
  }

  private def broker(beamBroker: BeamBroker): Unit = {
    beamBroker.getBrokersFederate.isDefined should be(true)
    val beamFederate = beamBroker.getBrokersFederate.get

    val (time1, message) = (beamFederate.sync(1), beamFederate.collectAny())
    time1 should be(1.0)
    message.mkString(",").trim should be("foo,123456")

    val response = List(Map("key" -> "foo", "value" -> 123456))
    beamFederate.publishJSON(response)
    val time2 = beamFederate.sync(2)
    time2 should be(2.0)

    beamFederate.close()
  }
}
