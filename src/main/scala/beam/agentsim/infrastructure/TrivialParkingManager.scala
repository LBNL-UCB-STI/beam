package beam.agentsim.infrastructure
import akka.actor.Actor
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall._
import org.matsim.api.core.v01.{Coord, Id}

// Abundant parking everywhere people require it. For testing.
class TrivialParkingManager extends Actor {
  private var nextStallNum = 0

  override def receive: Receive = {
    case request: ParkingInquiry =>
      val stall =
        new ParkingStall(
          Id.create(nextStallNum, classOf[ParkingStall]),
          StallAttributes(TAZTreeMap.emptyTAZId, Public, FlatFee, NoCharger, Any),
          request.destinationUtm,
          0.0,
          None
        )
      sender ! ParkingInquiryResponse(stall, request.requestId)
      nextStallNum += 1
  }
}

// Abundant parking, but only at one fixed location. For testing.
class AnotherTrivialParkingManager(location: Coord) extends Actor {
  private var nextStallNum = 0

  override def receive: Receive = {
    case request: ParkingInquiry =>
      val stall =
        new ParkingStall(
          Id.create(nextStallNum, classOf[ParkingStall]),
          StallAttributes(TAZTreeMap.emptyTAZId, Public, FlatFee, NoCharger, Any),
          location,
          0.0,
          None
        )
      sender ! ParkingInquiryResponse(stall, request.requestId)
      nextStallNum += 1
  }
}
