package beam.utils
import beam.agentsim.agents.vehicles.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

class SamplerTest extends FeatureSpec with Matchers with GivenWhenThen {
  private case class VehicleCategoryWithProbability(category: VehicleCategory, probability: Double)

  private val HighNumber = 100000
  private val Delta = 2
  private val sampler = new Sampler(0L)

  scenario("Sampler should calculate an weighted distribution of a list of elements") {
    Given("sequence of 3 objects with some probability")
    val probabilityBike = 20
    val probabilityBody = 30
    val probabilityCar = 50
    val objects = Seq(
      VehicleCategoryWithProbability(VehicleCategory.Bike, probabilityBike),
      VehicleCategoryWithProbability(VehicleCategory.Body, probabilityBody),
      VehicleCategoryWithProbability(VehicleCategory.Car, probabilityCar)
    )

    When(s"asked for an uniform sample $HighNumber of times")
    val result: Seq[VehicleCategoryWithProbability] =
      sampler.uniformSample(HighNumber, objects.map(x => (x, x.probability)))

    Then("should return a distribution proportional to the relative probability")
    val groupedWithPercentageSize = result
      .groupBy(x => x.category)
      .map(x => (x._1, (x._2.size.toDouble / HighNumber * 100).toInt))

    assert(
      groupedWithPercentageSize(VehicleCategory.Bike) === probabilityBike +- Delta
    )
    assert(
      groupedWithPercentageSize(VehicleCategory.Body) === probabilityBody +- Delta
    )
    assert(
      groupedWithPercentageSize(VehicleCategory.Car) === probabilityCar +- Delta
    )
  }

}
