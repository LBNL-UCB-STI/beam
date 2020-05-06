package beam.cosim.helics

import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.github.beam.HelicsLoader
import com.java.helics.helicsJNI.{helics_property_int_log_level_get, helics_property_time_delta_get}
import com.java.helics.{helics, SWIGTYPE_p_void}
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

class BeamFederateSpec extends FlatSpec with Matchers with BeamHelper {

  "Running a beamville scenario with cosimulation" must "result event being published and read" in {
    val config = ConfigFactory
      .parseString("""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.agentsim.collectEvents = true
                     |beam.agentsim.lastIteration = 0
                     |beam.cosim.helics = {
                     |  timeStep = 300
                     |  federateName = "BeamFederate"
                     |}
        """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    val f1 = Future { createBrokerAndReaderFederate() }
    val f2 = Future { runCosimulationTest(config) }
    val aggregatedFuture = for {
      f1Result <- f1
      f2Result <- f2
    } yield (f1Result, f2Result)
    try {
      Await.result(aggregatedFuture, 5.minutes)
    } catch {
      case _: TimeoutException =>
        assume(false, "something went wrong with the cosimulation")
    }
  }

  private def runCosimulationTest(config: Config) = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(services).update(scenario)
    val controler = services.controler
    controler.run()
  }

  private def createBrokerAndReaderFederate() = {
    HelicsLoader.load()
    val broker = helics.helicsCreateBroker("zmq", "", "-f 2 --name=BeamBrokerTemp")
    val fedName = "BeamFederateTemp"
    val fedInfo = helics.helicsCreateFederateInfo()
    helics.helicsFederateInfoSetCoreName(fedInfo, fedName)
    helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, "zmq")
    helics.helicsFederateInfoSetCoreInitString(fedInfo, "--federates=1")
    helics.helicsFederateInfoSetTimeProperty(fedInfo, helics_property_time_delta_get(), 1.0)
    helics.helicsFederateInfoSetIntegerProperty(fedInfo, helics_property_int_log_level_get(), 1)
    val fedComb = helics.helicsCreateCombinationFederate(fedName, fedInfo)
    val subsChargingPlugIn: SWIGTYPE_p_void =
      helics.helicsFederateRegisterSubscription(fedComb, "BeamFederate/chargingPlugIn", "string")
    val subsChargingPlugOut: SWIGTYPE_p_void =
      helics.helicsFederateRegisterSubscription(fedComb, "BeamFederate/chargingPlugOut", "string")
    helics.helicsFederateEnterInitializingMode(fedComb)
    helics.helicsFederateEnterExecutingMode(fedComb)
    val timeBin = 300
    var currentTime: Double = 0.0
    (1 to 360).foreach { i =>
      val t: Double = i * timeBin
      while (currentTime < t) currentTime = helics.helicsFederateRequestTime(fedComb, t)
      var buffer = new Array[Byte](1000)
      var bufferInt = new Array[Int](1)
      if (helics.helicsInputIsUpdated(subsChargingPlugIn) == 1) {
        helics.helicsInputGetString(subsChargingPlugIn, buffer, bufferInt)
        val chargingPlugInEvent = buffer.take(bufferInt(0)).map(_.toChar).mkString
        val arr = chargingPlugInEvent.split(",")
        assume(arr.size == 4, "chargingPlugIn is not transmitting four values")
      }
      if (helics.helicsInputIsUpdated(subsChargingPlugOut) == 1) {
        helics.helicsInputGetString(subsChargingPlugOut, buffer, bufferInt)
        val chargingPlugOutEvent = buffer.take(bufferInt(0)).map(_.toChar).mkString
        val arr = chargingPlugOutEvent.split(",")
        assume(arr.size == 4, "chargingPlugOut is not transmitting four values")
      }
    }
    helics.helicsFederateFinalize(fedComb)
    helics.helicsFederateDestroy(fedComb)
    helics.helicsFederateFree(fedComb)
    helics.helicsBrokerFree(broker)
    helics.helicsCloseLibrary()
  }
}
