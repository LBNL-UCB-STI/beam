package beam.router

import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.flatspec.AnyFlatSpec

class DoubleRouterTest extends AnyFlatSpec with BeamHelper {

  it should "have more trips for the second router transport" in {
    val iterationsAmount = 2
    val fileName = iterationsAmount + ".passengerPerTripSubway.csv"
//    val twoRoutersConf = "beamville/beam.conf"
    val twoRoutersConf = "beamville/beam-second-router.conf"
    val oneRouterConf = "beamville/beam.conf"
//    val oneRouterConf = "beamville/beam-second-router.conf"
//    val oneRouterConf = "sf-light/sf-light-0.5k.conf"
//    val twoRoutersConf = "sf-light/sf-light-0.5k-second-router.conf"
    val twoRouterTransitVehicleTypeVOTMultipliers = ""
//    val twoRouterTransitVehicleTypeVOTMultipliers = """beam.agentsim.agents.modalBehaviors.transitVehicleTypeVOTMultipliers = ["BUS-DEFAULT:10000.0","SUBWAY-DEFAULT:1.0"]"""
    val oneRouterTransitVehicleTypeVOTMultipliers = ""
//    val oneRouterTransitVehicleTypeVOTMultipliers = """beam.agentsim.agents.modalBehaviors.transitVehicleTypeVOTMultipliers = ["BUS-DEFAULT:10000.0","SUBWAY-DEFAULT:1.0"]"""
    val twoRouterName = "2r_bus-default_subway-1.0"
    val oneRouterName = "1r_bus-default_subway-1.0"

//    there are more trips on bus for BUS-DEFAULT:1.0 than for BUS-DEFAULT:10000.0

    val twoRoutersConfig = prepareConfig("test/input/" + twoRoutersConf, twoRouterName, iterationsAmount, twoRouterTransitVehicleTypeVOTMultipliers)
    val twoRoutersMatSimConfig = runBeamWithConfig(twoRoutersConfig)._1
    val twoRoutersIterationPath =
      s"${twoRoutersMatSimConfig.controler().getOutputDirectory}/ITERS/it.$iterationsAmount/"
//    val twoRoutersPassengersPerTrips = readPassengerPerTripFile(twoRoutersIterationPath + fileName)
//    assert(twoRoutersPassengersPerTrips.nonEmpty)

//    val oneRouterConfig = prepareConfig("test/input/" + oneRouterConf, iterationsAmount)
    val oneRouterConfig = prepareConfig("test/input/" + oneRouterConf, oneRouterName, iterationsAmount, oneRouterTransitVehicleTypeVOTMultipliers)
    val oneRouterMatSimConfig = runBeamWithConfig(oneRouterConfig)._1
    val oneRouterIterationPath = s"${oneRouterMatSimConfig.controler().getOutputDirectory}/ITERS/it.$iterationsAmount/"
    val oneRouterPassengersPerTrips = readPassengerPerTripFile(oneRouterIterationPath + fileName)
    assert(oneRouterPassengersPerTrips.nonEmpty)


//    assert(twoRoutersPassengersPerTrips.size > oneRouterPassengersPerTrips.size)
//    assert(!twoRoutersPassengersPerTrips.find(p => p == 0).isEmpty)
  }


  def prepareConfig(config: String, simulationName: String, iterationsAmount: Int, transitVehicleTypeVOTMultipliers: String): Config = {
    ConfigFactory
      .parseString(s"""
                      beam.agentsim.simulationName = $simulationName
                      beam.agentsim.lastIteration = $iterationsAmount
                      beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_transit_intercept = 1000
                      $transitVehicleTypeVOTMultipliers
                   """.stripMargin)
      .withFallback(testConfig(config))
      .resolve()
  }

  //todo: it doesn't read the file properly, needs to be fixed
  def readPassengerPerTripFile(filePath: String): Set[Double] = {
    var result = Set[Double]()
    GenericCsvReader.readAsSeq[Set[Double]](filePath) { row =>
      row.forEach { case (k, v) =>
        if (k != "hours") {
          result += v.toDouble
        }
      }
      result
    }
    result
  }

  //  1 bus-only -> only generates passengerPerTripBus file, have some passengers there and no passengerPerTripSubway file
  //  2 train-only -> generates passengerPerTripSubway file and no passengerPerTripBus file
  //  3 train+bus -> generates passengerPerTripSubway file and no passengerPerTripBus file

  it should "have passengerPerTripsBus graph only for one router and no graph for train" in {
    //    val config = prepareConfig("test/input/beamville/beam-r5-bus-only.conf")
    //    val matSimConfig = runBeamWithConfig(config)._1
    //    val iterationZeroPath = s"${matSimConfig.controler().getOutputDirectory}/ITERS/it.0/"
    //    val passengerPerTripFilePath = iterationZeroPath + "0.passengerPerTripBus.csv"
    //    val passengersPerTrips = readPassengerPerTripFile(passengerPerTripFilePath)
    //    assert(passengersPerTrips.nonEmpty)
    //    assert(!passengersPerTrips.find(p => p == 0).isEmpty)
    //    assert(!new java.io.File(iterationZeroPath + "0.passengerPerTripSubway.csv").exists())
  }

  //  todo: uncomment when csv reader resolved https://github.com/LBNL-UCB-STI/beam/issues/3457
  //  it should "have passengerPerTripsSubway graph only for one router and no graph for bus" in {
  //    val config = prepareConfig("test/input/beamville/beam-r5-train-only.conf")
  //    val matSimConfig = runBeamWithConfig(config)._1
  //    val iterationZeroPath = s"${matSimConfig.controler().getOutputDirectory}/ITERS/it.0/"
  //    val passengerPerTripFilePath = iterationZeroPath + "0.passengerPerTripSubway.csv"
  //    val passengersPerTrips = readPassengerPerTripFile(passengerPerTripFilePath)
  //    assert(passengersPerTrips.nonEmpty)
  //    assert(!passengersPerTrips.find(p => p == 0).isEmpty)
  //    assert(!new java.io.File(iterationZeroPath + "0.passengerPerTripBus.csv").exists())
  //  }

  it should "have passengerPerTripsSubway and passengerPerTripsBus from second router" in {
    //    val config = prepareConfig("test/input/beamville/beam-r5-train-and-bus.conf")
    //    val matSimConfig = runBeamWithConfig(config)._1
    //    val iterationZeroPath = s"${matSimConfig.controler().getOutputDirectory}/ITERS/it.0/"
    //    val passengerPerTripSubwayFilePath = iterationZeroPath + "0.passengerPerTripSubway.csv"
    //    val passengerPerTripBusFilePath = iterationZeroPath + "0.passengerPerTripBus.csv"
    //    todo: Add check on files data (passengers not 0)
    //    val passengersPerTrips = readPassengerPerTripFile(passengerPerTripSubwayFilePath)
    //    assert(passengersPerTrips.nonEmpty)
    //    assert(!passengersPerTrips.find(p => p == 0).isEmpty)
    //    assert(new java.io.File(passengerPerTripSubwayFilePath).exists())
    //    assert(new java.io.File(passengerPerTripBusFilePath).exists())
  }

  //  todo: uncomment when csv reader resolved https://github.com/LBNL-UCB-STI/beam/issues/3457
  //  it should "have passengerPerTripsSubway graph only for one router and no graph for bus" in {
  //    val config = prepareConfig("test/input/beamville/beam-r5-train-only.conf")
  //    val matSimConfig = runBeamWithConfig(config)._1
  //    val iterationZeroPath = s"${matSimConfig.controler().getOutputDirectory}/ITERS/it.0/"
  //    val passengerPerTripFilePath = iterationZeroPath + "0.passengerPerTripSubway.csv"
  //    val passengersPerTrips = readPassengerPerTripFile(passengerPerTripFilePath)
  //    assert(passengersPerTrips.nonEmpty)
  //    assert(!passengersPerTrips.find(p => p == 0).isEmpty)
  //    assert(!new java.io.File(iterationZeroPath + "0.passengerPerTripBus.csv").exists())
  //  }
}
