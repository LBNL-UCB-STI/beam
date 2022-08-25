package scripts.protocolvis

import beam.utils.logging.MessageLogger
import scripts.protocolvis.MessageReader._
import org.apache.commons.lang3.StringUtils

import java.nio.file.Path
import scala.util.matching.Regex

/**
  * @author Dmitry Openkov
  */
object SequenceDiagram {

  def process(messages: Iterator[RowData], output: Path): Unit = {
    PumlWriter.writeData(processMessages(messages), output)(serializer)
  }

  def processMessages(messages: Iterator[RowData]): IndexedSeq[PumlEntry] = {
    messages.foldLeft((IndexedSeq.empty[PumlEntry], -1)) { case ((acc, prevTick), row) =>
      val (entry, currentTick) = row match {
        case Event(sender, receiver, payload, _, tick, _) =>
          (
            Interaction(
              userFriendlyActorName(sender),
              userFriendlyActorName(receiver),
              userFriendlyPayload(payload)
            ),
            tick
          )
        case Message(sender, receiver, payload, tick, _) =>
          (
            Interaction(
              userFriendlyActorName(sender),
              userFriendlyActorName(receiver),
              userFriendlyPayload(payload)
            ),
            tick
          )
        case Transition(_, receiver, _, state, tick, _) =>
          (Note(userFriendlyActorName(receiver), state), tick)
      }
      if (currentTick >= 0 && currentTick != prevTick) {
        (acc :+ Delay(s"tick = $currentTick") :+ entry, currentTick)
      } else {
        (acc :+ entry, prevTick)
      }
    }
  }._1

  private val ScheduleTrigger: Regex = """ScheduleTrigger\((\w+\(.+\)),(Actor\[.+]),\d+\)""".r

  def parseScheduleTrigger(scheduleTrigger: String): String = {
    //ScheduleTrigger(EnrouteRefuelingTrigger(11944),Actor[akka://ClusterSystem/user/BeamMobsim.iteration/population/2/5#-1749848928],0)
    scheduleTrigger match {
      case ScheduleTrigger(trigger, actor) =>
        val triggerText = parseTrigger(trigger)
        val actorText = userFriendlyActorName(parseAkkaActor(actor))
        s"$triggerText for $actorText"
      case _ => throw new IllegalArgumentException(s"Cannot parse ScheduleTrigger $scheduleTrigger")
    }
  }

  private val AkkaActor: Regex = """Actor\[akka://(.+)]""".r

  def parseAkkaActor(akkaActor: String): Actor = {
    //Actor[akka://ClusterSystem/user/BeamMobsim.iteration/population/2/5#-1749848928]
    akkaActor match {
      case AkkaActor(path) => toActor(path)
      case _               => throw new IllegalArgumentException(s"Cannot parse AkkaActor $akkaActor")
    }
  }

  private def toActor(actorPath: String): Actor = {
    val path = actorPath.split('/')
    val parentPath = path.dropRight(1)
    val parent = MessageLogger.userFriendlyParent(parentPath)
    val ind = path.last.lastIndexOf('#')
    val name = if (ind >= 0) path.last.substring(0, ind) else path.last
    Actor(parent, name)
  }

  private val Trigger: Regex = """(\w+)\((\d+).*\)""".r

  def parseTrigger(trigger: String): String = {
    //EnrouteRefuelingTrigger(11944)
    //StartLegTrigger(9900,BeamLeg(WALK @ 9900,dur:0,path: ))
    //ChargingTimeOutTrigger(38349,3 (PHEV,-5.185524856616993km))
    trigger match {
      case Trigger(triggerName, tick) => s"$triggerName(tick=$tick)"
      case _                          => throw new IllegalArgumentException(s"Cannot parse Trigger $trigger")
    }
  }

  def parseTriggerWithId(payload: String): String = {
//    TriggerWithId(PlanEnergyDispatchTrigger(9900),47)
    val internal = payload.slice("TriggerWithId(".length, payload.length - 1)
    val commaIndex = internal.lastIndexOf(',')
    val triggerStr = internal.substring(0, commaIndex)
    val triggerId = internal.substring(commaIndex + 1)
    val trigger = parseTrigger(triggerStr)
    s"$trigger id=$triggerId"
  }

  def parseCompletionNotice(payload: String): String = {
//    CompletionNotice(46,Vector(ScheduleTrigger(PlanEnergyDispatchTrigger(9900),Actor[akka://ClusterSystem/user/BeamMobsim.iteration/ChargingNetworkManager#2033070023],0)))
    val internal = payload.slice("CompletionNotice(".length, payload.length - 1)
    val firstComma = internal.indexOf(',')
    val triggerId = internal.substring(0, firstComma)
    val seqOfTriggers = internal.slice(firstComma + 1, internal.length - 1)
    val firstBracket = seqOfTriggers.indexOf('(')
    val vectorOfTriggers = seqOfTriggers.slice(firstBracket + 1, internal.length - 1)
    val newTriggers = vectorOfTriggers.split("(,\\s)?ScheduleTrigger")
    val parsedTriggers = newTriggers
      .withFilter(!StringUtils.isEmpty(_))
      .map(trigStr => parseScheduleTrigger("ScheduleTrigger" + trigStr))
      .mkString(", ")
    if (parsedTriggers.nonEmpty) {
      s"Completion of $triggerId, $parsedTriggers"
    } else {
      s"Completion of $triggerId"
    }
  }

  def parseIllegalTrigger(payload: String): String = {
//    IllegalTriggerGoToError(Stuck Agent)
//    IllegalTriggerGoToError(Cannot schedule an event ScheduleTrigger(StartLegTrigger(10541,BeamLeg(CAR @ 10541,dur:141,path: 328 .. 112)),Actor[akka://ClusterSystem/user/BeamMobsim.iteration/population/2/5#1832017495],0) at tick 10541 when 'nowInSeconds' is at 12028})"    val internal = payload.slice("CompletionNotice(".length, payload.length - 1
    if (payload.startsWith("IllegalTriggerGoToError(Stuck Agent")) return payload
    val triggerStr = StringUtils.substringBetween(payload, "Cannot schedule an event ", " at tick ")
    val scheduleTrigger = parseScheduleTrigger(triggerStr)
    val errorInfo = StringUtils.substringBetween(payload, " at tick ", "})")

    s"IllegalTriggerGoToError $scheduleTrigger, scheduled at $errorInfo"
  }

  private val PayloadRegex: Regex = """\(?(\w+)\(([^()]+).*\)?""".r

  def userFriendlyPayload(payload: String): String = {
    if (payload.startsWith("ScheduleTrigger")) parseScheduleTrigger(payload)
    else if (payload.startsWith("CompletionNotice")) parseCompletionNotice(payload)
    else if (payload.startsWith("TriggerWithId")) parseTriggerWithId(payload)
    else if (payload.startsWith("IllegalTriggerGoToError")) parseIllegalTrigger(payload)
    else
      payload match {
        case PayloadRegex(external, _) => external
        case _                         => payload
      }
  }

  def userFriendlyPayloadSimplified(payload: String): String = {
    payload match {
      case PayloadRegex("TriggerWithId", internal) => internal
      case PayloadRegex(external, _)               => external
      case _                                       => payload
    }
  }

  private val personIdRegex: Regex = """\d+(-\d+)*""".r

  def userFriendlyActorName(actor: Actor): String = {
    val isParentPopulation = actor.parent == "population"
    val isParentHousehold = actor.parent.startsWith("population/")
    val looksLikeId = personIdRegex.pattern.matcher(actor.name).matches()
    val actorName = (isParentPopulation, isParentHousehold, looksLikeId) match {
      case (true, _, true)                                  => "Household"
      case (false, true, true)                              => "Person"
      case (false, true, false)                             => s"HouseholdFleetManager:${actor.name}"
      case _ if actor.name.startsWith("TransitDriverAgent") => "TransitDriverAgent"
      case _ if actor.name.startsWith("rideHailAgent")      => "RideHailAgent"
      case _                                                => actor.name
    }
    actorName.replace('-', '_')
  }

  sealed trait PumlEntry

  case class Note(over: String, value: String) extends PumlEntry
  case class Delay(value: String) extends PumlEntry

  case class Interaction(from: String, to: String, payload: String) extends PumlEntry

  def serializer: PumlEntry => String = {
    case Note(over, value)              => s"""hnote over "$over": $value"""
    case Delay(value)                   => s"""...$value..."""
    case Interaction(from, to, payload) => s""""$from" -> "$to": $payload"""
  }
}
