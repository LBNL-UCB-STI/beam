package beam.utils

import beam.agentsim.agents.choice.mode.PtFares
import beam.agentsim.agents.vehicles.{BeamVehicleType, FuelType, VehicleCategory, VehicleEnergy}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.utils.collections.QuadTree
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar

import java.time.ZonedDateTime
import scala.collection.concurrent.TrieMap

trait BeamScenarioForTest extends MockitoSugar {

  def getBeamScenario(pathToConfig: String, skimTravelTimesScalingFactor: Double): BeamScenario = {
    val beamConfig = BeamConfig(
      ConfigFactory
        .parseString(s"""
                        |beam.routing.skimTravelTimesScalingFactor =  $skimTravelTimesScalingFactor
        """.stripMargin)
        .withFallback(testConfig(pathToConfig))
        .resolve()
    )

    val vehicleType = BeamVehicleType(
      id = Id.create("car", classOf[BeamVehicleType]),
      seatingCapacity = 1,
      standingRoomCapacity = 1,
      lengthInMeter = 3,
      primaryFuelType = FuelType.Gasoline,
      primaryFuelConsumptionInJoulePerMeter = 0.1,
      primaryFuelCapacityInJoule = 0.1,
      vehicleCategory = VehicleCategory.Car
    )
    val vehicleTypes = Map(vehicleType.id                -> vehicleType)
    val fuelTypePrices = Map(vehicleType.primaryFuelType -> 10.0)
    val tazMap = mock[TAZTreeMap]
    when(tazMap.getTAZ(any[java.lang.Double](), any[java.lang.Double]()))
      .thenReturn(TAZ.DefaultTAZ)

    BeamScenario(
      fuelTypePrices = fuelTypePrices,
      vehicleTypes = vehicleTypes,
      TrieMap.empty,
      vehicleEnergy = mock[VehicleEnergy],
      beamConfig = beamConfig,
      dates = DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      ),
      ptFares = PtFares(List.empty),
      transportNetwork = mock[TransportNetwork],
      network = mock[Network],
      networks2 = None,
      tazTreeMap = tazMap,
      linkQuadTree = new QuadTree[Link](0, 0, 10, 10),
      linkIdMapping = Map.empty,
      linkToTAZMapping = Map.empty,
      modeIncentives = null,
      h3taz = null
    )
  }
}
