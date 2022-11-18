package beam.cosim.helics

import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.BeamHelper
import com.java.helics.helics
import org.scalatest.BeforeAndAfterAll
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

  "Running a broker and two federates" must "allow to send messages between federates" in {
    val coreType = "zmq"
    val broker = helics.helicsCreateBroker(coreType, "", s"-f 2 --name=Broker42")
    helics.helicsBrokerIsConnected(broker) should be > 0

    val brokerAddress = helics.helicsBrokerGetAddress(broker)

    def getFed(fedName: String, dataOutStreamName: String, dataInStreamName: String) = {
      val coreInitString = s"--federates=1 --broker_address=$brokerAddress"
      val fedInfo = createFedInfo(coreType, coreInitString, 1.0, 1)
      getFederate(fedName, fedInfo, 1000, 60, Some(dataOutStreamName), Some(dataInStreamName))
    }

    val fed1Name = "Fed1"
    val fed1OutStreamName = "LIST_ANY"
    val fed2Name = "Fed2"
    val fed2OutStreamName = "LIST_MAP_ANY"

    val beamFederate1 = getFed(fed1Name, fed1OutStreamName, f"$fed2Name/$fed2OutStreamName")
    val beamFederate2 = getFed(fed2Name, fed2OutStreamName, f"$fed1Name/$fed1OutStreamName")

    enterExecutionMode(10.seconds, beamFederate1, beamFederate2)

    val f1 = Future {
      federateSendReceive(beamFederate1, 15)
      federateSendReceive(beamFederate1, 25)
    }
    val f2 = Future {
      federateReceiveSend(beamFederate2, 15)
      federateReceiveSend(beamFederate2, 25)
    }
    val aggregatedFuture = for {
      federateResult1 <- f1
      federateResult2 <- f2
    } yield (federateResult1, federateResult2)
    try {
      Await.result(aggregatedFuture, 5.minutes)
    } catch {
      case _: TimeoutException =>
        fail("something went wrong with the co-simulation")
    }

    beamFederate1.close()
    beamFederate2.close()
  }

  private def federateSendReceive(beamFederate: BeamFederate, startingTime: Int): Unit = {
    val message = List("foo", 123456)
    beamFederate.publish(message)
    val time1 = beamFederate.sync(startingTime)
    time1 should be(startingTime)

    val nextStepTime = startingTime + 1
    val (time2, responseOpt) = (beamFederate.sync(nextStepTime), beamFederate.collectJSON())
    time2 should be(nextStepTime)

    responseOpt shouldBe defined
    val response = responseOpt.get
    response.head should contain("key" -> "foo")
    response.head should contain("value" -> 123456)
  }

  private def federateReceiveSend(beamFederate: BeamFederate, startingTime: Int): Unit = {
    val (time1, message) = (beamFederate.sync(startingTime), beamFederate.collectAny())
    time1 should be(startingTime)
    message.mkString(",").trim should be("foo,123456")

    val response = List(Map("key" -> "foo", "value" -> 123456))
    beamFederate.publishJSON(response)

    val nextStepTime = startingTime + 1
    val time2 = beamFederate.sync(nextStepTime)
    time2 should be(nextStepTime)
  }
}
