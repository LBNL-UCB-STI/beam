package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.events.Event


//trait LeavingParkingEventAttrs {
//  val EVENT_TYPE: String = "LeavingParkingEvent"
//  val ATTRIBUTE_PARKING_ID: String = "parkingId"
//  val ATTRIBUTE_SCORE: String = "score"
//}

class LeavingParkingEvent(time: Double, stall: ParkingStall, val score: Double) extends Event(time) with LeavingParkingEventAttrs{

  override def getEventType: String = LeavingParkingEventAttrs.EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes

    for{
      stallValues <- stall.stallValues
      parkingId <- stallValues.parkingId
    } yield{
      attr.put(LeavingParkingEventAttrs.ATTRIBUTE_PARKING_ID, parkingId.toString)
    }

    attr.put(LeavingParkingEventAttrs.ATTRIBUTE_SCORE, score.toString)

    attr
  }
}

//object LeavingParkingEvent extends LeavingParkingEventAttrs{
//}
