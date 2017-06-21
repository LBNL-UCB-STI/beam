package beam.agentsim.events.resources.vehicle

/**
  *@author dserdiuk on 6/18/17.
  */

case class GetVehicleLocationEvent(time: Double) extends org.matsim.api.core.v01.events.Event(time) {
  override def getEventType: String = getClass.getName
}


