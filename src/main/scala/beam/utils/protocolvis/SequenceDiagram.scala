package beam.utils.protocolvis

import beam.utils.protocolvis.MessageReader._

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
    messages.foldLeft((IndexedSeq.empty[PumlEntry], -1)) {
      case ((acc, prevTick), row) =>
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

  private val PayloadRegex: Regex = """\(?(\w+)\(([^()]+).*\)?""".r

  def userFriendlyPayload(payload: String): String = {
    payload match {
      case PayloadRegex("TriggerWithId", internal) => internal
      case PayloadRegex(external, _)               => external
      case _                                       => payload
    }
  }

  private val personIdRegex: Regex = """\d+(-\d+)+""".r

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
