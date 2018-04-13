package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.events.Event

class LeavingParkingEvent(time: Double, stall: ParkingStall, val score: Double) extends Event(time){

  import LeavingParkingEvent._

  override def getEventType: String = LeavingParkingEvent.EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes

    for{
      stallValues <- stall.stallValues
      parkingId <- stallValues.parkingId
    } yield{
      attr.put(PARKING_ID, parkingId.toString)
    }

    attr.put(SCORE, score.toString)

    attr
  }
}

object LeavingParkingEvent {
  val EVENT_TYPE = "LeavingParkingEvent"

  val PARKING_ID = "ParkingId"
  val SCORE = "Score"
}
