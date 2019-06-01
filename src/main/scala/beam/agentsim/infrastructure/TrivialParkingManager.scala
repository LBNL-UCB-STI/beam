package beam.agentsim.infrastructure
import akka.actor.Actor
import org.matsim.api.core.v01.{Coord, Id}

// Abundant parking everywhere people require it. For testing.
class TrivialParkingManager extends Actor {
  private var nextStallNum = 0

  override def receive: Receive = {
    case request: ParkingInquiry =>
      val stall = ParkingStall.defaultStall(request.destinationUtm)
      sender ! ParkingInquiryResponse(stall, request.requestId)
      nextStallNum += 1
  }
}

// Abundant parking, but only at one fixed location. For testing.
class AnotherTrivialParkingManager(location: Coord) extends Actor {
  private var nextStallNum = 0

  override def receive: Receive = {
    case request: ParkingInquiry =>
      val stall = ParkingStall.defaultStall(location)
      sender ! ParkingInquiryResponse(stall, request.requestId)
      nextStallNum += 1
  }
}
