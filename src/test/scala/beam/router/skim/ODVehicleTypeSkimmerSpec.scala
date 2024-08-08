package beam.router.skim

import beam.agentsim.agents.vehicles.VehicleCategory.{HeavyDutyTruck, LightDutyTruck}
import beam.router.skim.core.ODVehicleTypeSkimmer.{fromCsv, ODVehicleTypeSkimmerInternal, ODVehicleTypeSkimmerKey}
import beam.sim.BeamHelper
import beam.utils.EventReader._
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.core.config.Config
import org.matsim.core.utils.io.IOUtils
import org.scalatest.Inspectors.forAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * @author Dmitry Openkov
  */
class ODVehicleTypeSkimmerSpec extends AnyWordSpecLike with Matchers with BeamHelper {

  "OD Vehicle Type Skimmer" should {
    "produce correct skims for freight trips" in {
      val config = BeamHelper.updateConfigToCurrentVersion(
        ConfigFactory
          .parseString("""
          beam.agentsim.lastIteration = 1
          beam.agentsim.agents.freight.replanning.disableAfterIteration = 0
          beam.router.skim.origin-destination-vehicle-type-skimmer.vehicleCategories = "LightDutyTruck, HeavyDutyTruck"
                        """)
          .withFallback(testConfig("test/input/beamville/beam-freight.conf"))
          .resolve()
      )
      val (matSimConfig, _, _) = runBeamWithConfig(config)
      val skim0: Map[ODVehicleTypeSkimmerKey, ODVehicleTypeSkimmerInternal] =
        readSkims(matSimConfig, "skimsODVehicleType", 0)
      skim0.size should be > 5
      skim0.keys.map(_.vehicleCategory) should contain only (LightDutyTruck, HeavyDutyTruck)
      forAll(skim0.values) { value =>
        value.cost should be > 0.1
        value.cost should be < 15.0
        value.travelTimeInS should be > 100.0
        value.travelTimeInS should be < 500.0
        value.distanceInM should be > 2000.0
        value.distanceInM should be < 7000.0
        value.energy should be > 5e5
        value.energy should be < 3e8
      }
      val skim1: Map[ODVehicleTypeSkimmerKey, ODVehicleTypeSkimmerInternal] =
        readSkims(matSimConfig, "skimsODVehicleType", 1)
      val skimAgg: Map[ODVehicleTypeSkimmerKey, ODVehicleTypeSkimmerInternal] =
        readSkims(matSimConfig, "skimsODVehicleType_Aggregated", 1)
      skimAgg.keys.map(_.vehicleCategory) should contain only (LightDutyTruck, HeavyDutyTruck)
      def assertAggregation(key: ODVehicleTypeSkimmerKey, getter: ODVehicleTypeSkimmerInternal => Double) = {
        val expectedAggResult = (skim0.get(key), skim1.get(key)) match {
          case (Some(value), None)          => getter(value)
          case (None, Some(value))          => getter(value)
          case (Some(value0), Some(value1)) => (getter(value0) + getter(value1)) / 2
          case (None, None)                 => throw new IllegalArgumentException(key.toCsv)
        }
        val actual = getter(skimAgg(key))
        expectedAggResult should be(actual +- math.max(0.01 * math.abs(actual), 0.01))
      }
      forAll(skim0.keySet ++ skim1.keySet) { key =>
        assertAggregation(key, _.travelTimeInS)
        assertAggregation(key, _.generalizedTimeInS)
        assertAggregation(key, _.cost)
        assertAggregation(key, _.generalizedCost)
        assertAggregation(key, _.distanceInM)
        assertAggregation(key, _.payloadWeightInKg)
        assertAggregation(key, _.energy)
      }
      val intersectionKeys = skim0.keySet.intersect(skim1.keySet)
      forAll(intersectionKeys) { key =>
        skimAgg(key).observations should be(1)
        skimAgg(key).iterations should be(2)
      }
      forAll(skim0.keySet ++ skim1.keySet -- intersectionKeys) { key =>
        skimAgg(key).observations should be(1)
        skimAgg(key).iterations should be(1)
      }
    }
  }

  private def readSkims(matSimConfig: Config, fileName: String, iteration: Int) = {
    val filePath = getEventsFilePath(matSimConfig, fileName, "csv.gz", iteration).getAbsolutePath
    val reader = IOUtils.getBufferedReader(filePath)
    val skims: Map[ODVehicleTypeSkimmerKey, ODVehicleTypeSkimmerInternal] =
      new CsvSkimReader(filePath, fromCsv, logger).readSkims(reader)
    reader.close()
    skims
  }
}
