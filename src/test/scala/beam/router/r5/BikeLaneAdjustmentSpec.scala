package beam.router.r5

import java.nio.file.Path
import scala.util.Random
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.agentsim.agents.vehicles.VehicleCategory.{Body, Car, HeavyDutyTruck, LightDutyTruck, MediumDutyPassenger}
import beam.router.Modes.BeamMode
import beam.sim.config.BeamConfig
import beam.utils.{FixtureUtils, TestConfigUtils}
import com.typesafe.config.{Config, ConfigFactory}
import org.mockito.Mockito.{mock, when}
import org.scalatest.flatspec.AnyFlatSpec

class BikeLaneAdjustmentSpec extends AnyFlatSpec {

  import beam.router.r5.BikeLaneAdjustmentSpec._

  "BikeLanesAdjustment scaleFactor" should "calculate scalaFactor accordingly to linkId" in {
    val scaleFactor = 10d
    val identityScalaFactor = 1
    val linkIds = Set(1, 2, 3)

    val adjustment = new BikeLanesAdjustment(BikeLanesData(scaleFactor, linkIds))

    val randomValidLinkId = Random.shuffle(linkIds).head
    val randomInvalidLinkId = linkIds.max + 1

    assertResult(scaleFactor)(adjustment.scaleFactor(randomValidLinkId))
    assertResult(identityScalaFactor)(adjustment.scaleFactor(randomInvalidLinkId))
  }

  it should "calculate scalaFactor accordingly to beamMode/isEnabled" in {
    val scaleFactor = 10d
    val identityScalaFactor = 1
    val linkIds = Set(1, 2, 3)

    val notBikeMode = BeamMode.allModes.toSet - BeamMode.BIKE
    val randomMode = Random.shuffle(BeamMode.allModes).head
    val randomModeNotBike = Random.shuffle(notBikeMode).head

    val adjustment = new BikeLanesAdjustment(BikeLanesData(scaleFactor, linkIds))

    assertResult(scaleFactor)(adjustment.scaleFactor(beamMode = BeamMode.BIKE))
    assertResult(identityScalaFactor)(
      adjustment.scaleFactor(beamMode = randomMode, isScaleFactorEnabled = false)
    )
    assertResult(identityScalaFactor)(adjustment.scaleFactor(beamMode = randomModeNotBike))
  }

  it should "calculate scalaFactor accordingly to BeamVehicleType and linkId" in {
    val scaleFactor = 10d
    val identityScalaFactor = 1
    val linkIds = Set(1, 2, 3)

    val existingLinkId = Random.shuffle(linkIds).head
    val nonExistingLinkId = linkIds.max + 1
    val bikeVehicleType = mockBikeVehicleType
    val nonBikeVehicleType = mockNonBikeVehicleType

    val adjustment = new BikeLanesAdjustment(BikeLanesData(scaleFactor, linkIds))

    assertResult(scaleFactor)(adjustment.scaleFactor(bikeVehicleType, existingLinkId))
    assertResult(identityScalaFactor)(adjustment.scaleFactor(bikeVehicleType, nonExistingLinkId))
    assertResult(identityScalaFactor)(adjustment.scaleFactor(nonBikeVehicleType, existingLinkId))
    assertResult(identityScalaFactor)(adjustment.scaleFactor(nonBikeVehicleType, nonExistingLinkId))
  }

  "BikeLanesAdjustment beamConfig constructor" should "ignore invalid Ints as linkIds when reading file" in {
    val fileContent = Seq("linkId", "1", "invalidId", 2).mkString(System.lineSeparator())
    FixtureUtils.usingTemporaryTextFileWithContent(fileContent) { filePath =>
      assertResult(Set(1, 2)) {
        BikeLanesAdjustment.loadBikeLaneLinkIds(filePath.toString)
      }
    }
  }

  it should "load constructor properly from config file" in {
    val validLinkIds = Seq(1, 2)
    val randomValidLinkId = Random.shuffle(validLinkIds).head
    val invalidLinkId = validLinkIds.max + 1
    val fileContent = Seq("linkId", validLinkIds.head, "invalidId", validLinkIds(1)).mkString(System.lineSeparator())
    val predefinedScaleFactor = Random.nextDouble()
    val identifyScaleFactor = 1

    FixtureUtils.usingTemporaryTextFileWithContent(fileContent) { filePath =>
      val config = BeamConfig(minimumBikeLaneConfig(filePath, predefinedScaleFactor))

      val bikeLanesAdjustment = BikeLanesAdjustment(config)

      assertResult(predefinedScaleFactor)(bikeLanesAdjustment.scaleFactor(randomValidLinkId))
      assertResult(identifyScaleFactor)(bikeLanesAdjustment.scaleFactor(invalidLinkId))
    }
  }

}

private object BikeLaneAdjustmentSpec {

  def minimumBikeLaneConfig(filePathLinkIds: Path, scaleFactor: Double): Config = {
    val bikeLanesConfig =
      s"""beam.routing.r5.bikeLaneScaleFactor = $scaleFactor
         |beam.routing.r5.bikeLaneLinkIdsFilePath = "${filePathLinkIds.toString.replace('\\', '/')}"""".stripMargin
    ConfigFactory.parseString(bikeLanesConfig).withFallback(TestConfigUtils.minimumValidBeamConfig)
  }

  def mockBikeVehicleType: BeamVehicleType = {
    val result = mock(classOf[BeamVehicleType])
    when(result.vehicleCategory).thenReturn(VehicleCategory.Bike)
    result
  }

  def mockNonBikeVehicleType: BeamVehicleType = {
    val result = mock(classOf[BeamVehicleType])
    when(result.vehicleCategory).thenReturn(Random.shuffle(nonBikeVehicleTypeOptions).head)
    result
  }

  private val nonBikeVehicleTypeOptions = Seq(
    Body,
    Car,
    MediumDutyPassenger,
    LightDutyTruck,
    HeavyDutyTruck
  )

}
