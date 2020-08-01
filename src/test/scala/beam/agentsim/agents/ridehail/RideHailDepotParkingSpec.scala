package beam.agentsim.agents.ridehail

import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.ShutdownEvent
import org.matsim.core.controler.listener.ShutdownListener
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

class RideHailDepotParkingSpec extends FlatSpec with Matchers with BeamHelper {

  val configString: String =
    """
           |beam.outputs.events.fileOutputFormats = "csv.gz"
           |beam.agentsim.agentSampleSizeAsFractionOfPopulation = 1.0
           |
           |beam.physsim.skipPhysSim = true
           |beam.agentsim.lastIteration = 1
           |beam.agentsim.h3taz = {
           |  enabled = false
           |}
           |beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId = "RHCAV_BEV"
           |beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypePrefix = "RHCAV"
           |beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 0.5
           |beam.agentsim.agents.rideHail.initialization.parking.filePath = "test/input/beamville/depot/taz-depot-50kw.csv"
           |beam.agentsim.agents.parking.minSearchRadius = 250.00
           |beam.agentsim.agents.parking.maxSearchRadius = 8046.72
           |beam.agentsim.agents.parking.mulitnomialLogit.params.rangeAnxietyMultiplier = -0.5
           |beam.agentsim.agents.parking.mulitnomialLogit.params.distanceMultiplier = -0.086
           |beam.agentsim.agents.parking.mulitnomialLogit.params.parkingPriceMultiplier = -0.5
           |beam.agentsim.agents.parking.mulitnomialLogit.params.refuelWaitTime = 0.0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transfer = 0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept = 0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_transit_intercept = 0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept = 0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_transit_intercept = 0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept = -10.0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_pooled_intercept = 10.0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept = 0
           |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept = 0
           |beam.router.skim = {
           |  keepKLatestSkims = 1
           |  writeSkimsInterval = 1
           |  writeAggregatedSkimsInterval = 1
           |  drive-time-skimmer {
           |    name = "drive-time-skimmer"
           |    fileBaseName = "skimsTravelTimeObservedVsSimulated"
           |    timeBin = 3600
           |  }
           |  origin-destination-skimmer {
           |    name = "od-skimmer"
           |    fileBaseName = "skimsOD"
           |    writeAllModeSkimsForPeakNonPeakPeriodsInterval = 0
           |    writeFullSkimsInterval = 0
           |    timeBin = 3600
           |  }
           |  taz-skimmer {
           |    name = "taz-skimmer"
           |    fileBaseName = "skimsTAZ"
           |    timeBin = 3600
           |  }
           |}
        """.stripMargin

  "Running a car-sharing-only scenario with abundant cars" must "result in everybody driving" in {
    val configDefault = ConfigFactory
      .parseString(configString)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
    val refuelWaitTimePath = "beam.agentsim.agents.parking.mulitnomialLogit.params.refuelWaitTime"
    val config0 = configDefault.withValue(refuelWaitTimePath, ConfigValueFactory.fromAnyRef(0.0)).resolve()
    val config1 = configDefault.withValue(refuelWaitTimePath, ConfigValueFactory.fromAnyRef(-1.0)).resolve()
    val sumRWTNull = runBEVRHCAVTest(config0)
    val sumRWTNeg = runBEVRHCAVTest(config1)
    assert(sumRWTNeg < sumRWTNull, "Sum refuel wait time should decrease when the value of refuelWaitTime is negative")
  }

  private def runBEVRHCAVTest(config: Config): Double = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    var services: Option[BeamServices] = None
    var sumOfWaitTimes: Double = 0.0
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addControlerListenerBinding().toInstance(new ShutdownListener {
            override def notifyShutdown(event: ShutdownEvent): Unit = {
              val skim = services.get.skims.taz_skimmer.getLatestSkim("RideHailManager", "RefuelWaitTime")
              sumOfWaitTimes = skim.map(x => x._2.value * x._2.observations).sum / Math
                .max(skim.map(_._2.observations).sum, 1)
            }
          })
        }
      }
    )
    services = Some(injector.getInstance(classOf[BeamServices]))
    DefaultPopulationAdjustment(services.get).update(scenario)
    val controler = services.get.controler
    controler.run()
    sumOfWaitTimes
  }

}
