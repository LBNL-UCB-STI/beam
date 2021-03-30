package beam.utils.protocolvis

import beam.utils.protocolvis.MessageReader._

import scala.util.matching.Regex

/**
  * @author Dmitry Openkov
  */
object SequenceDiagram {

  def processMessages(messages: IndexedSeq[RowData]): IndexedSeq[PumlEntry] = {
    fixTransitionEvent(messages)
      .map {
        case Event(sender, receiver, payload, _, _) =>
          Interaction(userFriendlyActorName(sender), userFriendlyActorName(receiver), userFriendlyPayload(payload))
        case Message(sender, receiver, payload, _) =>
          Interaction(userFriendlyActorName(sender), userFriendlyActorName(receiver), userFriendlyPayload(payload))
        case Transition(_, receiver, _, state, _) =>
          Note(userFriendlyActorName(receiver), state)
      }
  }

  private val PayloadRegex: Regex = """(\w+)\(([^()]+).*""".r

  private def userFriendlyPayload(payload: String): String = {
    payload match {
      case PayloadRegex("TriggerWithId", internal) => internal
      case PayloadRegex(external, _)               => external
      case _                                       => payload
    }
  }

  private val personIdRegex: Regex = """\d+(-\d+)+""".r

  private def userFriendlyActorName(actor: Actor) = {
    val isParentPopulation = actor.parent == "population"
    val isParentHousehold = actor.parent.startsWith("population/")
    val looksLikeId = personIdRegex.pattern.matcher(actor.name).matches()
    (isParentPopulation, isParentHousehold, looksLikeId) match {
      case (true, _, true)      => "Household"
      case (false, true, true)  => "Person"
      case (false, true, false) => s"HouseholdFleetManager:${actor.name}"
      case _                    => actor.name
    }
  }

  /* we need to switch transitions and the corresponding events  */
  private def fixTransitionEvent(messages: IndexedSeq[RowData]): IndexedSeq[RowData] = {
    val (fixedSeq, _) = messages.foldLeft((IndexedSeq.empty[RowData], IndexedSeq.empty[RowData])) {
      //put a transition to the buffer
      case ((result, _), row: Transition) => (result, IndexedSeq(row))
      //put the event before the transition
      case ((result, buffer), row: Event) if buffer.nonEmpty => (result ++ (row +: buffer), IndexedSeq.empty)
      //any messages between the transition and the event are kept
      case ((result, buffer), row) if buffer.nonEmpty => (result, buffer :+ row)
      //just regular messages: put them in the sequence
      case ((result, buffer), row) => (result :+ row, buffer)
    }
    fixedSeq
  }

  sealed trait PumlEntry

  case class Note(over: String, value: String) extends PumlEntry

  case class Interaction(from: String, to: String, payload: String) extends PumlEntry

  def serializer: PumlEntry => String = {
    case Note(over, value)              => s"""rnote over "$over": $value"""
    case Interaction(from, to, payload) => s""""$from" -> "$to": $payload"""
  }
}
