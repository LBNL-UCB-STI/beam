package beam.cosim.helics

import java.util.concurrent.atomic.AtomicInteger

import beam.agentsim.events.ChargingPlugInEvent
import beam.cosim.helics.BeamFederate._
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.java.helics.helicsJNI.{helics_property_int_log_level_get, helics_property_time_delta_get}
import com.java.helics.{SWIGTYPE_p_void, helics, helics_data_type}
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import spray.json.{JsString, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.Try

class BeamFederateSpec extends FlatSpec with Matchers with BeamHelper with BeforeAndAfterAll {
  override def beforeAll(): Unit = {
    BeamFederate.loadHelics
  }

  override def afterAll(): Unit = {
    BeamFederate.unloadHelics
  }

  "Running a beamville scenario with cosimulation" must "result event being published and read" in {
    val config = ConfigFactory
      .parseString(
        """
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.agentsim.chargingNetworkManager.gridConnectionEnabled = true
                     |beam.agentsim.lastIteration = 0
                     |beam.agentsim.agents.vehicles.vehicleTypesFilePath = "test/input/beamville/vehicleTypesForMoreFrequentCharges.csv"
                     |beam.cosim.helics = {
                     |  timeStep = 300
                     |  federateName = "BeamFederate"
                     |}
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val aggregatedFuture = for {
      f1 <- Future { createBrokerAndReaderFederate() }
      f2 <- Future { runCosimulationTest(config) }
    } yield (f1, f2)
    try {
      val (f1Result, f2Result) = Await.result(aggregatedFuture, 5.minutes)
      f1Result should be > 0
      f2Result.size should be > 0
    } catch {
      case _: TimeoutException =>
        fail("something went wrong with the cosimulation")
    }
  }

  private def runCosimulationTest(config: Config): List[Map[String, Any]] = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    var beamFederate: Option[BeamFederate] = None
    var outputMap: List[Map[String, Any]] = List.empty[Map[String, Any]]
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case e: ChargingPlugInEvent =>
                  beamFederate.foreach {
                    fed => fed.publish(List(Map[String, Any] (
                      "vehid" -> e.vehId.toString,
                      "tick" -> e.tick,
                      "primaryFuelLevel" -> e.primaryFuelLevel
                    )))
                  }
                case _ =>
              }
            }
          })
          addControlerListenerBinding().toInstance(new IterationEndsListener {
            override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
              event match {
                case _: IterationEndsEvent =>
                  beamFederate.foreach {
                    fed =>
                      outputMap = fed.syncAndMoveToNextTimeStep(30*3600)
                  }
              }
            }
          })
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(services).update(scenario)
    val controler = services.controler
    beamFederate = Some(BeamFederate(services, "BeamFederate", 300, 1000, "chargingPlugIn", "BeamFederateTemp/chargingPlugInACK"))
    controler.run()
    outputMap
  }

  private def createBrokerAndReaderFederate(): Int = {
    val broker = helics.helicsCreateBroker("zmq", "", s"-f 2 --name=BeamBrokerTemp")
    val isHelicsBrokerConnected = helics.helicsBrokerIsConnected(broker)
    isHelicsBrokerConnected should be > 0

    val chargingPlugInEvents: AtomicInteger = new AtomicInteger(0)
    val fedName = "BeamFederateTemp"
    val fedInfo = helics.helicsCreateFederateInfo()
    helics.helicsFederateInfoSetCoreName(fedInfo, fedName)
    helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, "zmq")
    helics.helicsFederateInfoSetCoreInitString(fedInfo, "--federates=1")
    helics.helicsFederateInfoSetTimeProperty(fedInfo, helics_property_time_delta_get(), 1.0)
    helics.helicsFederateInfoSetIntegerProperty(fedInfo, helics_property_int_log_level_get(), 1)
    val fedComb = BeamFederate.synchronized {
      helics.helicsCreateCombinationFederate(fedName, fedInfo)
    }
    val subsChargingPlugIn: SWIGTYPE_p_void =
      helics.helicsFederateRegisterSubscription(fedComb, "BeamFederate/chargingPlugIn", "string")

    val pubFed = helics.helicsFederateRegisterPublication(fedComb, "BeamFederateTemp/chargingPlugInACK", helics_data_type.helics_data_type_string, "")

    helics.helicsFederateEnterInitializingMode(fedComb)
    helics.helicsFederateEnterExecutingMode(fedComb)

    try {
      val timeBin = 300
      var currentTime: Double = 0.0
      (0 to 360).foreach { i =>
        val t: Double = i * timeBin
        while (currentTime < t) currentTime = helics.helicsFederateRequestTime(fedComb, t)
        val buffer = new Array[Byte](1000)
        val bufferInt = new Array[Int](1)
        if (helics.helicsInputIsUpdated(subsChargingPlugIn) == 1) {
          helics.helicsInputGetString(subsChargingPlugIn, buffer, bufferInt)
          val chargingPlugInEvent = buffer.take(bufferInt(0)).map(_.toChar).mkString
          val arr = chargingPlugInEvent.split(",")
          require(arr.size == 4, "chargingPlugIn is not transmitting four values")
          chargingPlugInEvents.incrementAndGet()
        }
      }
      helics.helicsFederatePublishJSON(pubFed, Map[String, Any]("response" -> JsString("OK")).toJson.compactPrint)
    } finally {
      Try(helics.helicsFederateFinalize(fedComb))
      Try(helics.helicsFederateDestroy(fedComb))
      Try(helics.helicsFederateFree(fedComb))
      Try(helics.helicsBrokerFree(broker))
    }
    chargingPlugInEvents.get()
  }
}
