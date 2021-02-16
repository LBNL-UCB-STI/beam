package beam.api.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailManager.VehicleId
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.ridehail.kpis.DefaultKpiRegistry
import beam.agentsim.agents.ridehail.kpis.KpiRegistry.Kpi
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.infrastructure.ParkingStall
import beam.sim.{BeamServices, RideHailFleetInitializer}
import beam.utils.Registry
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

/**
  * API providing hooks for extending the RideHailManager.
  */
trait RidehailManagerCustomizationAPI {

  protected var rideHailManager: RideHailManager = _

  def init(rideHailManager: RideHailManager) = {
    this.rideHailManager = rideHailManager
  }

  /**
    * This method is called when a messages is not handled by the receive block in the [[RideHailManager]].
    * @param msg
    * @param sender
    */
  def receiveMessageHook(msg: Any, sender: ActorRef)

  /**
    * This method is called when a [[Finish]] message is received by the [[RideHailManager]].
    */
  def receiveFinishMessageHook()

  /**
    * This method is called immediately at the start of continueRepositioning(tick: Int) in the [[RideHailManager]].
    * @param tick
    */
  def beforeContinueRepositioningHook(tick: Int)

  /**
    * This method is called in continueRepositioning(tick: Int) and is part of the [[RideHailManager]] and allows the
    * implementer to specify additional vehicles for refueling and assign a depot to them.
    * @param idleVehicles
    * @param tick
    * @return
    */
  def identifyAdditionalVehiclesForRefuelingDuringContinueRepositioningAndAssignDepotHook(
    idleVehicles: mutable.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], ParkingStall)]

  /**
    * This method allows to set an additional overhead to a beamLeg during continueRepositioning(tick: Int).
    * @param vehicleId
    * @return
    */
  def beamLegOverheadDuringContinueRepositioningHook(
    vehicleId: RideHailManager.VehicleId
  ): Int

  /**
    * This method is called at the end of continueRepositioning(tick: Int) and allows to perform location updates.
    * @param vehicleId
    * @param destination
    */
  def processVehicleLocationUpdateAtEndOfContinueRepositioningHook(
    vehicleId: Id[Vehicle],
    destination: Coord
  )

  /**
    * This method is called during initializeRideHailFleet() and as such can be used to extend operations related to
    * ride hail fleet initialization.
    * @param beamServices
    * @param rideHailAgentInitializers
    * @param maxTime
    */
  def initializeRideHailFleetHook(
    beamServices: BeamServices,
    rideHailAgentInitializers: scala.IndexedSeq[RideHailFleetInitializer.RideHailAgentInitializer],
    maxTime: Int
  )

  /**
    * This method is called as part of createTravelProposal(alloc: VehicleMatchedToCustomers) and can be used to update the [[PassengerSchedule]].
    * @param passengerSchedule
    * @return
    */
  def updatePassengerScheduleDuringCreateTravelProposalHook(passengerSchedule: PassengerSchedule): PassengerSchedule

  /**
    * This method is called when a TAZSkimsCollectionTrigger(tick) message is received and can be used to collect relevant
    * data from the [[RidehailManager]] to use in the current or future iteration.
    * @param tick
    */
  def recordCollectionData(tick: Int): Unit

  /**
    * This method is called in [[RideHailModifyPassengerScheduleManager]], when the method sendNewPassengerScheduleToVehicle reaches the success case.
    * @param vehicleId
    * @param passengerSchedule
    */
  def sendNewPassengerScheduleToVehicleWhenSuccessCaseHook(vehicleId: Id[Vehicle], passengerSchedule: PassengerSchedule)

  /**
    * This method gives back the KPI registry, from which all known KPIs can be accessed.
    * @return
    */
  def getKpiRegistry: Registry[Kpi]

}

/**
  * This is the default implementation of [[RidehailManagerCustomizationAPI]].
  */
class DefaultRidehailManagerCustomization extends RidehailManagerCustomizationAPI {
  override def receiveMessageHook(msg: Any, sender: ActorRef): Unit = {}

  override def beforeContinueRepositioningHook(tick: Int): Unit = {}

  override def identifyAdditionalVehiclesForRefuelingDuringContinueRepositioningAndAssignDepotHook(
    idleVehicles: mutable.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], ParkingStall)] = { Vector.empty }

  override def beamLegOverheadDuringContinueRepositioningHook(vehicleId: VehicleId): Int = { 0 }

  override def processVehicleLocationUpdateAtEndOfContinueRepositioningHook(
    vehicleId: Id[Vehicle],
    destination: Coord
  ): Unit = {}

  override def initializeRideHailFleetHook(
    beamServices: BeamServices,
    rideHailAgentInitializers: IndexedSeq[RideHailFleetInitializer.RideHailAgentInitializer],
    maxTime: Int
  ): Unit = {}

  override def updatePassengerScheduleDuringCreateTravelProposalHook(
    passengerSchedule: PassengerSchedule
  ): PassengerSchedule = { passengerSchedule }

  override def recordCollectionData(tick: Int): Unit = {}

  override def sendNewPassengerScheduleToVehicleWhenSuccessCaseHook(
    vehicleId: Id[Vehicle],
    passengerSchedule: PassengerSchedule
  ): Unit = {}

  override def getKpiRegistry: Registry[Kpi] =
    DefaultKpiRegistry

  override def receiveFinishMessageHook(): Unit = {}

}
