package beam.analysis

import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, DrivesVehicle}
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleCategory}
import beam.router.BeamRouter
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  CAR,
  CAV,
  DRIVE_TRANSIT,
  RIDE_HAIL,
  RIDE_HAIL_POOLED,
  RIDE_HAIL_TRANSIT,
  WALK,
  WALK_TRANSIT
}
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamServices
import beam.sim.metrics.MetricsSupport
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.events.IterationEndsEvent

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.JavaConverters._

object TmpBikeAnalysis extends MetricsSupport with LazyLogging {

  def processMobInq(id: Id[Person], vc: VehicleCategory, availableVehicles: List[BeamVehicle]): Unit = {
    val provided = availableVehicles.headOption.map(_.beamVehicleType.vehicleCategory.toString).getOrElse("")
    mobInq.add(Seq(id, vc, availableVehicles.size, provided))
  }

  def initialRouting(id: Id[PersonAgent], response: BeamRouter.RoutingResponse): Unit =
    routingMap += id -> response

  def notifyIterationEnds(event: IterationEndsEvent, beamServices: BeamServices): Unit = {
    val bdiPath = beamServices.matsimServices.getControlerIO.getOutputFilename("bike_debug_info.csv")
    val writer = CsvWriter(
      bdiPath,
      "num_vehicles",
      "num_bikes",
      "num_shared_bikes",
      "num_cars",
      "num_body",
      "initial_trips",
      "initial_WALK",
      "initial_WALK_TRANSIT",
      "initial_BIKE",
      "initial_BIKE_TRANSIT",
      "initial_CAR",
      "initial_DRIVE_TRANSIT",
      "trips",
      "filtered",
      "BIKE",
      "BIKE_TRANSIT",
      "RIDE_HAIL",
      "RIDE_HAIL_POOLED",
      "RIDE_HAIL_TRANSIT",
      "DRIVE_TRANSIT",
      "WALK_TRANSIT",
      "CAR",
      "CAV",
      "WALK",
      "chosen_mode"
    )
    writer.writeAllAndClose(backend.asScala)
    val path2 = beamServices.matsimServices.getControlerIO.getOutputFilename("new_veh_debug_info.csv")
    val writer2 = CsvWriter(
      path2,
      "num_vehicles",
      "num_cars",
    )
    writer2.writeAllAndClose(vehicles.asScala)
    val path3 = beamServices.matsimServices.getControlerIO.getOutputFilename("mob_inq_debug_info.csv")
    val writer3 = CsvWriter(
      path3,
      "person_id",
      "category",
      "total_veh",
      "provided",
    )
    writer3.writeAllAndClose(mobInq.asScala)
  }

  private val routingMap = TrieMap.empty[Id[PersonAgent], BeamRouter.RoutingResponse]
  private val backend = new ConcurrentLinkedQueue[Seq[Any]]
  private val vehicles = new ConcurrentLinkedQueue[Seq[Any]]
  private val mobInq = new ConcurrentLinkedQueue[Seq[Any]]

  def processData(newlyAvailableBeamVehicles: Vector[DrivesVehicle.VehicleOrToken]) = {
    vehicles.add(
      Seq(newlyAvailableBeamVehicles.size, newlyAvailableBeamVehicles.count(_.streetVehicle.mode == BeamMode.CAR))
    )
  }

  def processData(
    id: Id[PersonAgent],
    choosesModeData: ChoosesMode.ChoosesModeData,
    filtered: Vector[EmbodiedBeamTrip],
    chosenTrip: EmbodiedBeamTrip
  ): Unit = {
    val maybeResponse = routingMap.remove(id)
    val initialTrips = maybeResponse
      .map { rr =>
        IndexedSeq(
          rr.itineraries.size,
          rr.itineraries.count(_.tripClassifier == BeamMode.WALK),
          rr.itineraries.count(_.tripClassifier == BeamMode.WALK_TRANSIT),
          rr.itineraries.count(_.tripClassifier == BeamMode.BIKE),
          rr.itineraries.count(_.tripClassifier == BeamMode.BIKE_TRANSIT),
          rr.itineraries.count(_.tripClassifier == BeamMode.CAR),
          rr.itineraries.count(_.tripClassifier == BeamMode.DRIVE_TRANSIT),
        )
      }
      .getOrElse(IndexedSeq(null, null, null, null, null, null, null))
    val numAv = choosesModeData.availablePersonalStreetVehicles.size
    val numBikes = choosesModeData.availablePersonalStreetVehicles.count(
      v => v.streetVehicle.mode == BeamMode.BIKE && !v.vehicle.managerInfo.managerType.isShared
    )
    val numSharedBikes = choosesModeData.availablePersonalStreetVehicles.count(
      v => v.streetVehicle.mode == BeamMode.BIKE && v.vehicle.managerInfo.managerType.isShared
    )
    val numCars = choosesModeData.availablePersonalStreetVehicles.count(v => v.streetVehicle.mode == BeamMode.CAR)
    val numBody = choosesModeData.availablePersonalStreetVehicles.count(v => v.streetVehicle.mode == BeamMode.WALK)
    val rr = choosesModeData.routingResponse.get
    val trips = rr.itineraries.size
    val filt = filtered.size
    val bikeTrips = filtered.count(_.tripClassifier == BeamMode.BIKE)
    val bikeTransitTrips = filtered.count(_.tripClassifier == BeamMode.BIKE_TRANSIT)
    val chosenMode = chosenTrip.tripClassifier
    backend.add(
      Seq(
        numAv,
        numBikes,
        numSharedBikes,
        numCars,
        numBody,
        initialTrips(0),
        initialTrips(1),
        initialTrips(2),
        initialTrips(3),
        initialTrips(4),
        initialTrips(5),
        initialTrips(6),
        trips,
        filt,
        bikeTrips,
        bikeTransitTrips,
        filtered.count(_.tripClassifier == BeamMode.RIDE_HAIL),
        filtered.count(_.tripClassifier == BeamMode.RIDE_HAIL_POOLED),
        filtered.count(_.tripClassifier == BeamMode.RIDE_HAIL_TRANSIT),
        filtered.count(_.tripClassifier == BeamMode.DRIVE_TRANSIT),
        filtered.count(_.tripClassifier == BeamMode.WALK_TRANSIT),
        filtered.count(_.tripClassifier == BeamMode.CAR),
        filtered.count(_.tripClassifier == BeamMode.CAV),
        filtered.count(_.tripClassifier == BeamMode.WALK),
        chosenMode
      )
    )
  }

}
