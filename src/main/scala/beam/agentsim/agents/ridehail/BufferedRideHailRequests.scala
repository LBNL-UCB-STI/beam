package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

class BufferedRideHailRequests(
  val scheduler: ActorRef,
  val triggerId: Long,
  val tick: Double
) {

  //println(s"creating BufferedRideHailRequests, tick: $tick")

  // TODO: make private (don't allow external access to these)
  // the completion triggers for the current timeout
  private var nextBufferedTriggerMessages = Vector[BeamAgentScheduler.ScheduleTrigger]()

  // these are the vehicleIds with which we are overwriting things
  private var setOfReplacementVehicles =
    mutable.Set[Id[Vehicle]]()

  var numberOfOverwriteRequestsOpen: Int = 0

  def setNumberOfOverwriteRequests(numRequests: Integer) = {
    numberOfOverwriteRequestsOpen = numRequests
  }

  def decreaseNumberOfOpenOverwriteRequests() = {
    numberOfOverwriteRequestsOpen = numberOfOverwriteRequestsOpen - 1
  }

  def registerVehicleAsReplacementVehicle(vehicleId: Id[Vehicle]): Unit = {
    setOfReplacementVehicles add vehicleId

    DebugLib.emptyFunctionForSettingBreakPoint()
  }

  def replacementVehicleReservationCompleted(vehicleId: Id[Vehicle]): Unit = {
    setOfReplacementVehicles.remove(vehicleId)
  }

  def isReplacementVehicle(vehicleId: Id[Vehicle]): Boolean = {
    setOfReplacementVehicles.contains(vehicleId)
  }

  def addTriggerMessages(messages: Vector[BeamAgentScheduler.ScheduleTrigger]) = {

    nextBufferedTriggerMessages = nextBufferedTriggerMessages ++ messages
  }

  def isBufferedRideHailRequestProcessingOver: Boolean = {

    numberOfOverwriteRequestsOpen == 0 && setOfReplacementVehicles.size == 0
  }

  def tryClosingBufferedRideHailRequestWaive() = {

    if (isBufferedRideHailRequestProcessingOver) {
      closingBufferedRideHailRequestWaive()
    }

  }

  def closingBufferedRideHailRequestWaive() = {

    if (nextBufferedTriggerMessages.size > 1) {
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    scheduler ! CompletionNotice(
      triggerId,
      nextBufferedTriggerMessages
    )

  }

}
