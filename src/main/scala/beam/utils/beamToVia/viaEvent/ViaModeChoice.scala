package beam.utils.beamToVia.viaEvent

sealed trait ModeChoiceType
object ActionStart extends ModeChoiceType
object ActionEnd extends ModeChoiceType

object ViaModeChoice {
  def start(time: Double, person: String, link: Int) = new ViaModeChoice(time, person, link, ActionStart)
  def end(time: Double, person: String, link: Int) = new ViaModeChoice(time, person, link, ActionEnd)
}

//<event time="19285.0" type="actend"     person="2371" link="s185044"    actType="home"  />
//<event time="20055.0" type="actstart"   person="7237" link="sss383289"  actType="work"  />

case class ViaModeChoice(
  var time: Double,
  person: String,
  link: Int,
  eventType: ModeChoiceType,
  actionName: String = "modeChoice"
) extends ViaEvent {

  def toXml: scala.xml.Elem =
    eventType match {
      case ActionStart =>
        <event time={timeString} type="actstart" person={person} link={link.toString} actType={actionName} />
      case ActionEnd =>
        <event time={timeString} type="actend" person={person} link={link.toString} actType={actionName} />
    }
}
