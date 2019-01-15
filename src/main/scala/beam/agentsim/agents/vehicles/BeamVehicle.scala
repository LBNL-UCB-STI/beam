package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.ParkingStall.ChargingType
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.model.BeamLeg
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.common.GeoUtils.{Straight, TurningDirection}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.households.Household
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.vehicles.Vehicle

/**
  * A [[BeamVehicle]] is a state container __administered__ by a driver ([[PersonAgent]]
  * implementing [[beam.agentsim.agents.modalbehaviors.DrivesVehicle]]). The passengers in the [[BeamVehicle]]
  * are also [[BeamVehicle]]s, however, others are possible). The
  * reference to a parent [[BeamVehicle]] is maintained in its carrier. All other information is
  * managed either through the MATSim [[Vehicle]] interface or within several other classes.
  *
  * @author saf
  * @since Beam 2.0.0
  */
// XXXX: This is a class and MUST NOT be a case class because it contains mutable state.
// If we need immutable state, we will need to operate on this through lenses.

// TODO: safety for
class BeamVehicle(
  val id: Id[BeamVehicle],
  val powerTrain: Powertrain,
  val beamVehicleType: BeamVehicleType
) extends StrictLogging {

  var manager: Option[ActorRef] = None

  var spaceTime: SpaceTime = _

  var fuelLevelInJoules: Option[Double] = Some(beamVehicleType.primaryFuelCapacityInJoule)

  /**
    * The [[PersonAgent]] who is currently driving the vehicle (or None ==> it is idle).
    * Effectively, this is the main controller of the vehicle in space and time in the scenario environment;
    * whereas, the manager is ultimately responsible for assignment and (for now) ownership
    * of the vehicle as a physical property.
    */
  var driver: Option[ActorRef] = None

  var reservedStall: Option[ParkingStall] = None
  var stall: Option[ParkingStall] = None

  /**
    * Called by the driver.
    */
  def unsetDriver(): Unit = {
    driver = None
  }

  /**
    * Only permitted if no driver is currently set. Driver has full autonomy in vehicle, so only
    * a call of [[unsetDriver]] will remove the driver.
    *
    * @param newDriver incoming driver
    */
  def becomeDriver(newDriver: ActorRef): Unit = {
    if (driver.isEmpty) {
      driver = Some(newDriver)
    } else {
      // This is _always_ a programming error.
      // A BeamVehicle is only a data structure, not an Actor.
      // It must be ensured externally, by other means, that only one agent can access
      // it at any time, e.g. by using a ResourceManager etc.
      // Also, this exception is only a "best effort" error detection.
      // Technically, it can also happen that it is _not_ thrown in the failure case,
      // as this method is not synchronized.
      // Don't try to catch this exception.
      throw new RuntimeException("Trying to set a driver where there already is one.")
    }
  }

  def setReservedParkingStall(newStall: Option[ParkingStall]): Unit = {
    reservedStall = newStall
  }

  def useParkingStall(newStall: ParkingStall): Unit = {
    stall = Some(newStall)
  }

  def unsetParkingStall(): Unit = {
    stall = None
  }

  def useFuel(beamLeg: BeamLeg, beamServices: BeamServices): Double = {
    val distanceInMeters = beamLeg.travelPath.distanceInM
    val network =
      if (beamServices.matsimServices != null) Some(beamServices.matsimServices.getScenario.getNetwork) else None
    val fuelConsumption = network map (
      n => BeamVehicle.collectFuelConsumptionData(beamLeg, beamVehicleType, n)
    )
    fuelLevelInJoules match {
      case Some(fLevel) =>
        val energyConsumed = fuelConsumption match {
          case Some(consumption) => powerTrain.estimateConsumptionInJoules(consumption)
          case None              => powerTrain.estimateConsumptionInJoules(distanceInMeters)
        }
        if (fLevel < energyConsumed) {
          logger.warn(
            "Vehicle {} does not have sufficient fuel to travel {} m, only enough for {} m, setting fuel level to 0",
            id,
            distanceInMeters,
            fLevel / powerTrain.estimateConsumptionInJoules(1)
          )
        }
        fuelLevelInJoules = Some(Math.max(fLevel - energyConsumed, 0.0))
        energyConsumed
      case None =>
        0.0
    }
  }

  def addFuel(fuelInJoules: Double): Unit = fuelLevelInJoules foreach { fLevel =>
    fuelLevelInJoules = Some(fLevel + fuelInJoules)
  }

  /**
    *
    * @return refuelingDuration
    */
  def refuelingSessionDurationAndEnergyInJoules(): (Long, Double) = {
    stall match {
      case Some(theStall) =>
        ChargingType.calculateChargingSessionLengthAndEnergyInJoules(
          theStall.attributes.chargingType,
          fuelLevelInJoules.get,
          beamVehicleType.primaryFuelCapacityInJoule,
          beamVehicleType.rechargeLevel2RateLimitInWatts,
          beamVehicleType.rechargeLevel3RateLimitInWatts,
          None
        )
      case None =>
        (0, 0.0) // if we are not parked, no refueling can occur
    }
  }

  def getState: BeamVehicleState =
    BeamVehicleState(
      fuelLevelInJoules.getOrElse(Double.NaN),
      fuelLevelInJoules.getOrElse(Double.NaN) / powerTrain.estimateConsumptionInJoules(1),
      driver,
      stall
    )

  def toStreetVehicle: StreetVehicle =
    StreetVehicle(id, beamVehicleType.vehicleTypeId, spaceTime, BeamMode.CAR, true)

}

object BeamVehicle {

  def noSpecialChars(theString: String): String =
    theString.replaceAll("[\\\\|\\\\^]+", ":")

  def createId[A](id: Id[A], prefix: Option[String] = None): Id[BeamVehicle] = {
    createId(id.toString,prefix)
  }
  def createId[A](id: String, prefix: Option[String]): Id[BeamVehicle] = {
    Id.create(s"${prefix.map(_ + "-").getOrElse("")}${id}", classOf[BeamVehicle])
  }

  case class BeamVehicleState(
    fuelLevel: Double,
    remainingRangeInM: Double,
    driver: Option[ActorRef],
    stall: Option[ParkingStall]
  )

  case class FuelConsumptionData(
    linkId: Int,
    linkCapacity: Double,
    linkLength: Double,
    averageSpeed: Double,
    freeFlowSpeed: Double,
    linkArrivalTime: Long,
    vehicleId: String,
    vehicleType: BeamVehicleType,
    turnAtLinkEnd: TurningDirection,
    numberOfStops: Int
  )

  /**
    * Organizes the fuel consumption data table
    * @param beamLeg Instance of beam leg
    * @param network the transport network instance
    * @return list of fuel consumption objects generated
    */
  def collectFuelConsumptionData(
    beamLeg: BeamLeg,
    vehicleType: BeamVehicleType,
    network: Network
  ): IndexedSeq[FuelConsumptionData] = {
    if (beamLeg.mode.isTransit & !Modes.isOnStreetTransit(beamLeg.mode)) {
      Vector.empty
    } else {
      val networkLinks = network.getLinks
      val linkIds = beamLeg.travelPath.linkIds
      val linkTravelTimes: IndexedSeq[Int] = beamLeg.travelPath.linkTravelTime
      // generate the link arrival times for each link ,by adding cumulative travel times of previous links
      val linkArrivalTimes: Seq[Int] = for (i <- linkTravelTimes.indices) yield {
        i match {
          case 0 => beamLeg.startTime
          case _ =>
            beamLeg.startTime + (try {
              linkTravelTimes(i - 1)
            } catch {
              case _: Exception => 0
            })
        }
      }
      val nextLinkIds = linkIds.takeRight(linkIds.size - 1)
      linkIds.zipWithIndex.map { idAndIdx =>
        val id = idAndIdx._1
        val idx = idAndIdx._2
        val travelTime = linkTravelTimes(idx)
        val arrivalTime = linkArrivalTimes(idx)
        val currentLink: Option[Link] = Option(networkLinks.get(Id.createLinkId(id)))
        val averageSpeed = try {
          if (travelTime > 0) currentLink.map(_.getLength).getOrElse(0.0) / travelTime else 0
        } catch {
          case _: Exception => 0.0
        }
        // get the next link , and calculate the direction to be taken based on the angle between the two links
        val nextLink = if (idx < nextLinkIds.length) {
          Some(networkLinks.get(Id.createLinkId(nextLinkIds(idx))))
        } else {
          currentLink
        }
        val turnAtLinkEnd = currentLink match {
          case Some(curLink) =>
            GeoUtils.getDirection(GeoUtils.vectorFromLink(curLink), GeoUtils.vectorFromLink(nextLink.get))
          case None =>
            Straight
        }
        val numStops = turnAtLinkEnd match {
          case Straight => 0
          case _        => 1
        }
        FuelConsumptionData(
          linkId = id,
          linkCapacity = currentLink.map(_.getCapacity).getOrElse(0),
          linkLength = currentLink.map(_.getLength).getOrElse(0),
          averageSpeed = averageSpeed,
          freeFlowSpeed = currentLink.map(_.getFreespeed).getOrElse(0),
          linkArrivalTime = arrivalTime,
          vehicleId = id.toString,
          vehicleType = vehicleType,
          turnAtLinkEnd = turnAtLinkEnd,
          numberOfStops = numStops
        )
      }
    }
  }
}
