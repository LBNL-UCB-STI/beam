package beam.agentsim.infrastructure
import akka.actor.Actor
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import org.matsim.api.core.v01.Id

class TrivialParkingManager extends Actor {
  private var nextStallNum = 0

  override def receive: Receive = {
    case request: ParkingInquiry =>
      val stall =
        new ParkingStall(Id.create(nextStallNum, classOf[ParkingStall]), null, request.destinationUtm, 0.0, None)
      sender ! ParkingInquiryResponse(stall, request.requestId)
  }
}
