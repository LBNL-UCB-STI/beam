package beam.agentsim.events.resources.vehicle

import beam.router.RoutingModel.BeamLeg
import com.eaio.uuid.{UUID, UUIDGen}
import org.matsim.api.core.v01.Id

/**
  *@author dserdiuk on 6/18/17.
  */

case class GetVehicleLocationEvent(time: Double) extends org.matsim.api.core.v01.events.Event(time) {
  override def getEventType: String = getClass.getName
}

case class AlightingNotice(noticeId: Id[AlightingNotice], dropOffPoint: BeamLeg) {
  def this(dropOffPoint: BeamLeg) = this(Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[AlightingNotice]), dropOffPoint)
}

case class BoardingNotice(noticeId: Id[BoardingNotice], pickUpPoint: BeamLeg) {
  def this(pickUpPoint: BeamLeg) = this(Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[BoardingNotice]), pickUpPoint)
}

case class AlightingConfirmation(noticeId: Id[AlightingNotice])
case class BoardingConfirmation(noticeId: Id[BoardingNotice])

case object EnterVehicle

case object ExitVehicle


