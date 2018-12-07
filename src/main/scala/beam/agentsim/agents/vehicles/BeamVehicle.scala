package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.Resource
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleState, FuelConsumption}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.ParkingStall.ChargingType
import beam.router.model.BeamLeg
import beam.sim.BeamServices
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.{Coord, Id}
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
  val beamVehicleType: BeamVehicleType
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

  var reservedStall: Option[ParkingStall] = None
  var stall: Option[ParkingStall] = None

  override def getId: Id[BeamVehicle] = id

  /**
    * Called by the driver.
    */
  def unsetDriver(): Unit = {
    driver = None
  }

  /**
    * Only permitted if no driver is currently set. Driver has full autonomy in vehicle, so only
    * a call of [[unsetDriver]] will remove the driver.
    * Send back appropriate response to caller depending on protocol.
    *
    * @param newDriverRef incoming driver
    */
  def becomeDriver(
    newDriverRef: ActorRef
  ): BecomeDriverResponse = {
    if (driver.isEmpty) {
      driver = Some(newDriverRef)
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

  /**
    * Computes the angle between two coordinates
    * @param source source coordinates
    * @param destination destination coordinates
    * @return angle between the coordinates (in radians).
    */
  private def computeAngle(source: Coord, destination: Coord): Double =
    Math.toRadians(Math.atan2(destination.getY - source.getY, destination.getX - source.getX))

  /**
    * Get the desired direction to be taken , based on the angle between the coordinates
    * @param source source coordinates
    * @param destination destination coordinates
    * @return Direction to be taken ( L / SL / HL / R / HR / SR / S)
    */
  private def getDirection(source: Coord, destination: Coord): String = {
    if (!((source.getX == destination.getX) || (source.getY == destination.getY))) {
      val radians = this.computeAngle(source, destination)
      radians match {
        case _ if radians < 0.174533 || radians >= 6.10865 => "R" // Right
        case _ if radians >= 0.174533 & radians < 1.39626  => "SR" // Soft Right
        case _ if radians >= 1.39626 & radians < 1.74533   => "S" // Straight
        case _ if radians >= 1.74533 & radians < 2.96706   => "SL" // Soft Left
        case _ if radians >= 2.96706 & radians < 3.31613   => "L" // Left
        case _ if radians >= 3.31613 & radians < 3.32083   => "HL" // Hard Left
        case _ if radians >= 3.32083 & radians < 6.10865   => "HR" // Hard Right
        case _                                             => "S" // default => Straight
      }
    } else "S"
  }

  /**
    * Generates the fuel consumption data table
    * @param beamLeg Instance of beam leg
    * @param network the transport network instance
    * @return list of fuel consumption objects generated
    */
  private def generateFuelConsumptionData(beamLeg: BeamLeg, network: Network): List[FuelConsumption] = {
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
    val networkLinks = network.getLinks.values().asScala
    val nextLinkIds = linkIds.toList.takeRight(linkIds.size - 1)
    (linkIds zip nextLinkIds zip linkTravelTimes zip linkArrivalTimes flatMap { tuple =>
      val (((id, nextId), travelTime), arrivalTime) = tuple
      networkLinks.find(x => x.getId.toString.toInt == id) map { currentLink =>
        val averageSpeed = try {
          if (travelTime > 0) currentLink.getLength / travelTime else 0
        } catch {
          case _: Exception => 0.0
        }
        // get the next link , and calculate the direction to be taken based on the angle between the two links
        val nextLink: Option[Link] = networkLinks.find(x => x.getId.toString.toInt == nextId)
        val turnAtLinkEnd = getDirection(currentLink.getCoord, nextLink.map(_.getCoord).getOrElse(new Coord(0.0, 0.0)))
        FuelConsumption(
          linkId = id,
          linkCapacity = currentLink.getCapacity,
          averageSpeed = averageSpeed,
          freeFlowSpeed = currentLink.getFreespeed,
          linkArrivalTime = arrivalTime,
          vehicleId = id.toString,
          vehicleType = beamVehicleType.vehicleTypeId,
          turnAtLinkEnd = turnAtLinkEnd,
          numberOfStops =
            if (turnAtLinkEnd.equalsIgnoreCase("NA")) 0
            else 1
        )
      }
    }).toList
  }

  def useFuel(beamLeg: BeamLeg, beamServices: BeamServices): Double = {
    val distanceInMeters = beamLeg.travelPath.distanceInM
    val scenario = beamServices.matsimServices.getScenario
    val fuelConsumption: Option[List[FuelConsumption]] = Some(
      this.generateFuelConsumptionData(beamLeg, scenario.getNetwork)
    )
    fuelLevelInJoules match {
      case Some(fLevel) =>
        val energyConsumed = fuelConsumption match {
          case Some(consumption) => powerTrain.estimateConsumptionInJoules(distanceInMeters, consumption)
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

  case class FuelConsumption(
    linkId: Int,
    linkCapacity: Double,
    averageSpeed: Double,
    freeFlowSpeed: Double,
    linkArrivalTime: Long,
    vehicleId: String,
    vehicleType: String,
    turnAtLinkEnd: String,
    numberOfStops: Int
  )

}
