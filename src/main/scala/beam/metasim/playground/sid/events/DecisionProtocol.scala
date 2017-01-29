package beam.metasim.playground.sid.events

import org.matsim.api.core.v01.TransportMode
import org.matsim.core.controler.events.StartupEvent

/**
  *
  * @author sfeygin on 1/28/17
  */
object DecisionProtocol {
  // TODO: Inherit from "BeamChoice" or some such abstract parent trait


//  case class MATSimControlerEvent(controlerEvent: ControlerEvent) extends MATSimEvent
//  case class MATSimMobSimEvent(mobSimEvent: Event)

  trait ModeChoice
  trait ActivityChoice


  case class ChooseMode(modeType: TransportMode) extends ModeChoice
}
