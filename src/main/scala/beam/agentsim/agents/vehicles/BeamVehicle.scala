package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.Resource
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleState, FuelConsumptionData}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.ParkingStall.ChargingType
import beam.router.Modes
import beam.router.model.BeamLeg
import beam.sim.BeamServices
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households.Household
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._

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
  val initialMatsimAttributes: Option[ObjectAttributes],
  val beamVehicleType: BeamVehicleType,
  val houseHoldId: Option[Id[Household]]
) extends Resource[BeamVehicle]
    with StrictLogging {

  var fuelLevelInJoules: Option[Double] = Some(beamVehicleType.primaryFuelCapacityInJoule)

  /**
    * The [[PersonAgent]] who is currently driving the vehicle (or None ==> it is idle).
    * Effectively, this is the main controller of the vehicle in space and time in the scenario environment;
    * whereas, the manager is ultimately responsible for assignment and (for now) ownership
    * of the vehicle as a physical property.
    */
  var driver: Option[ActorRef] = None
  var driverId: Option[String] = None

  var reservedStall: Option[ParkingStall] = None
  var stall: Option[ParkingStall] = None

  override def getId: Id[BeamVehicle] = id

  /**
    * Called by the driver.
    */
  def unsetDriver(): Unit = {
    driver = None
    driverId = None
  }

  /**
    * Only permitted if no driver is currently set. Driver has full autonomy in vehicle, so only
    * a call of [[unsetDriver]] will remove the driver.
    * Send back appropriate response to caller depending on protocol.
    *
    * @param newDriverRef incoming driver
    */
  def becomeDriver(
    newDriverRef: ActorRef,
    newDriverId: String
  ): BecomeDriverResponse = {
    if (driver.isEmpty) {
      driver = Some(newDriverRef)
      driverId = Some(newDriverId)
      BecomeDriverOfVehicleSuccess
    } else if (driver.get.path.compareTo(newDriverRef.path) == 0) {
      NewDriverAlreadyControllingVehicle
    } else {
      DriverAlreadyAssigned(driver.get)
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
    val fuelConsumption: Option[List[FuelConsumptionData]] = network map (
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

}

object BeamVehicle {

  def noSpecialChars(theString: String): String =
    theString.replaceAll("[\\\\|\\\\^]+", ":")

  def createId[A](id: Id[A], prefix: Option[String] = None): Id[BeamVehicle] = {
    Id.create(s"${prefix.map(_ + "-").getOrElse("")}${id.toString}", classOf[BeamVehicle])
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
    turnAtLinkEnd: String,
    numberOfStops: Int
  )

  /**
    * Get the desired direction to be taken , based on the angle between the coordinates
    * @param source source coordinates
    * @param destination destination coordinates
    * @return Direction to be taken ( L / SL / HL / R / HR / SR / S)
    */
  def getDirection(source: Coord, destination: Coord): String = {
    val radians = computeAngle(source, destination)
    radians match {
      case _ if radians < 0.174533 || radians >= 6.10865 => "S" // Straight
      case _ if radians >= 0.174533 & radians < 1.39626  => "SL" // Soft Left
      case _ if radians >= 1.39626 & radians < 1.74533   => "L" // Left
      case _ if radians >= 1.74533 & radians < 3.14159   => "HL" // Hard Left
      case _ if radians >= 3.14159 & radians < 4.53785   => "HR" // Hard Right
      case _ if radians >= 4.53785 & radians < 4.88692   => "R" // Right
      case _ if radians >= 4.88692 & radians < 6.10865   => "SR" // Soft Right
      case _                                             => "S" // default => Straight
    }
  }

  /**
    * Generate the vector coordinates from the link nodes
    * @param link link in the network
    * @return vector coordinates
    */
  def vectorFromLink(link: Link): Coord = {
    new Coord(
      link.getToNode.getCoord.getX - link.getFromNode.getCoord.getX,
      link.getToNode.getCoord.getY - link.getFromNode.getCoord.getY
    )
  }

  /**
    * Computes the angle between two coordinates
    * @param source source coordinates
    * @param destination destination coordinates
    * @return angle between the coordinates (in radians).
    */
  def computeAngle(source: Coord, destination: Coord): Double = {
    val rad = Math.atan2(
      source.getX * destination.getY - source.getY * destination.getX,
      source.getX * destination.getX - source.getY * destination.getY
    )
    if (rad < 0) {
      rad + 3.141593 * 2.0
    } else {
      rad
    }
  }

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
  ): List[FuelConsumptionData] = {
    if (beamLeg.mode.isTransit & !Modes.isOnStreetTransit(beamLeg.mode)) {
      List()
    } else {
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
      val nextLinkIds = linkIds.toList.takeRight(linkIds.size - 1)
      linkIds.zipWithIndex.map { idAndIdx =>
        val id = idAndIdx._1
        val idx = idAndIdx._2
        val travelTime = linkTravelTimes(idx)
        val arrivalTime = linkArrivalTimes(idx)
        val currentLink: Option[Link] = Option(network.getLinks.get(Id.createLinkId(id)))
        val averageSpeed = try {
          if (travelTime > 0) currentLink.map(_.getLength).getOrElse(0.0) / travelTime else 0
        } catch {
          case _: Exception => 0.0
        }
        // get the next link , and calculate the direction to be taken based on the angle between the two links
        val nextLink = if (idx < nextLinkIds.length) {
          Some(network.getLinks.get(Id.createLinkId(nextLinkIds(idx))))
        } else {
          currentLink
        }
        val turnAtLinkEnd = currentLink match {
          case Some(curLink) =>
            getDirection(vectorFromLink(curLink), vectorFromLink(nextLink.get))
          case None =>
            "S"
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
          numberOfStops =
            if (turnAtLinkEnd.equalsIgnoreCase("NA")) 0
            else 1
        )
      }.toList
    }
  }

}
