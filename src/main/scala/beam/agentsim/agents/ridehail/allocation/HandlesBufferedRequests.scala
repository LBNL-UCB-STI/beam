package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StopDrivingIfNoPassengerOnBoardReply

trait HandlesBufferedRequests {

  def updateVehicleAllocations(tick: Double): Unit

  def handleRideCancellationReply(reply: StopDrivingIfNoPassengerOnBoardReply): Unit

}
