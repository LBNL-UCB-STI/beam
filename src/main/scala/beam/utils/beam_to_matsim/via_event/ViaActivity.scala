package beam.utils.beam_to_matsim.via_event

sealed trait ActType
object ActionStart extends ActType
object ActionEnd extends ActType

object ViaActivity {

  def start(time: Double, person: String, link: Int, actionName: String) =
    new ViaActivity(time, person, link, ActionStart, actionName)

  def end(time: Double, person: String, link: Int, actionName: String) =
    new ViaActivity(time, person, link, ActionEnd, actionName)
}

//<event time="19285.0" type="actend"     person="2371" link="s185044"    actType="home"  />
//<event time="20055.0" type="actstart"   person="7237" link="sss383289"  actType="work"  />

case class ViaActivity(
  var time: Double,
  person: String,
  link: Int,
  eventType: ActType,
  actionName: String
) extends ViaEvent {

  def toXmlString: String =
    eventType match {
      case ActionStart =>
        s"""<event time=$timeString type="actstart" person=$person link=${link.toString} actType=$actionName />"""
      case ActionEnd =>
        s"""<event time=$timeString type="actend" person=$person link=${link.toString} actType=$actionName />"""
    }

  def toXml: scala.xml.Elem =
    eventType match {
      case ActionStart =>
        <event time={timeString} type="actstart" person={person} link={link.toString} actType={actionName} />
      case ActionEnd =>
        <event time={timeString} type="actend" person={person} link={link.toString} actType={actionName} />
    }
}
