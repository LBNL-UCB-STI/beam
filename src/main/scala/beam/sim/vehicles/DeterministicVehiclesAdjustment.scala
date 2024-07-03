package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.sim.BeamScenario
import beam.utils.scenario.{HouseholdId, VehicleInfo}
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.{Coord, Id}

import scala.util.Random

case class DeterministicVehiclesAdjustment(
  beamScenario: BeamScenario,
  householdIdToVehicleIds: Map[HouseholdId, Iterable[VehicleInfo]]
) extends VehiclesAdjustment {

  private lazy val totalNumberOfVehicles: Map[VehicleCategory, IndexedSeq[BeamVehicle]] =
    beamScenario.privateVehicles.values
      .groupBy(_.beamVehicleType.vehicleCategory)
      .mapValues(x => x.toIndexedSeq)
      .view
      .force

  private lazy val vehiclesByCategory: Map[VehicleCategory, IndexedSeq[BeamVehicleType]] =
    beamScenario.vehicleTypes.values
      .groupBy(_.vehicleCategory)
      .mapValues(_.toIndexedSeq)
      .view
      .force

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    (0 until numVehicles).map(_ => sampleAnyVehicle(vehicleCategory, realDistribution)).toList
  }

  private def sampleAnyVehicle(
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): BeamVehicleType = {
    if (totalNumberOfVehicles.isEmpty) {
      logger.debug("Private vehicles haven't been created to sample from yet. Sampling a vehicle uniformly")
      val indexToTake = (realDistribution.sample() * vehiclesByCategory(vehicleCategory).length).floor.toInt
      vehiclesByCategory(vehicleCategory)(indexToTake)
    } else {
      val indexToTake = (realDistribution.sample() * totalNumberOfVehicles(vehicleCategory).size).floor.toInt
      totalNumberOfVehicles(vehicleCategory)(indexToTake).beamVehicleType
    }

  }

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistribution,
    householdId: Option[HouseholdId]
  ): List[BeamVehicleType] = {
    // In both of these cases it would be better to have an integer distribution rather than real, but this works fine
    if (numVehicles == 0) {
      List.empty[BeamVehicleType]
    } else {
      householdId match {
        case Some(hhId) =>
          val vehiclesToSampleFrom = householdIdToVehicleIds
            .getOrElse(hhId, Iterable.empty[VehicleInfo])
            .flatMap(vtid => beamScenario.vehicleTypes.get(Id.create(vtid.vehicleTypeId, classOf[BeamVehicleType])))
            .filter(_.vehicleCategory == vehicleCategory)
            .toList
          if (numVehicles == vehiclesToSampleFrom.length) {
            vehiclesToSampleFrom
          } else if (vehiclesToSampleFrom.isEmpty) {
            (0 until numVehicles).map(_ => sampleAnyVehicle(vehicleCategory, realDistribution)).toList
          } else if (vehiclesToSampleFrom.length > numVehicles) {
            DeterministicVehiclesAdjustment.sampleSmart(vehiclesToSampleFrom, numVehicles)
          } else {
            logger.warn(
              f"Household $householdId has $numVehicles in the household file but ${vehiclesToSampleFrom.length} " +
              f"in the vehicles input file. Sampling available vehicles with replacement"
            )
            realDistribution
              .sample(numVehicles)
              .flatMap(x =>
                beamScenario.vehicleTypes.get(
                  Id.create(
                    vehiclesToSampleFrom((x * vehiclesToSampleFrom.length).floor.toInt).id,
                    classOf[BeamVehicleType]
                  )
                )
              )
              .toList
          }
        case _ => (0 until numVehicles).map(_ => sampleAnyVehicle(vehicleCategory, realDistribution)).toList
      }
    }

  }
}

object DeterministicVehiclesAdjustment {

  // Anyone have a better way of using a uniform real distribution to sample without replacement?
  def sample[T](
    realDistribution: UniformRealDistribution,
    listOfObjectsToSample: List[T],
    numberOfObjectsToSample: Int
  ): List[T] = {
    realDistribution
      .sample(listOfObjectsToSample.length)
      .zipWithIndex
      .sortBy(_._1)
      .map(_._2)
      .take(numberOfObjectsToSample)
      .map(listOfObjectsToSample(_))
      .toList
  }

  def sampleSimple[T](
    listOfObjectsToSample: List[T],
    numberOfObjectsToSample: Int
  ): List[T] = {
    Random.shuffle(listOfObjectsToSample).take(numberOfObjectsToSample)
  }

  def sampleForLongList[T](
    listOfObjectsToSample: List[T],
    numberOfObjectsToSample: Int
  ): List[T] = {
    val sampled = scala.collection.mutable.HashSet.empty[Int]
    while (sampled.size < numberOfObjectsToSample) {
      sampled += Random.nextInt(listOfObjectsToSample.length)
    }
    sampled.map(listOfObjectsToSample(_)).toList
  }

  def sampleSmart[T](
    listOfObjectsToSample: List[T],
    numberOfObjectsToSample: Int
  ): List[T] = {
    if (numberOfObjectsToSample * 4 < listOfObjectsToSample.length) {
      sampleForLongList(listOfObjectsToSample, numberOfObjectsToSample)
    } else {
      sampleSimple(listOfObjectsToSample, numberOfObjectsToSample)
    }
  }

}
