package beam.utils.protocolvis

import beam.utils.protocolvis.MessageReader._
import beam.utils.protocolvis.SequenceDiagram.{userFriendlyActorName, userFriendlyPayload}

/**
  * @author Dmitry Openkov
  */
object ActorAsState {

  def processMessages(messages: Iterator[RowData]): IndexedSeq[StateDiagramEntry] = {
    val transitions = messages
      .collect {
        case Event(sender, receiver, payload, _, triggerId) => Message(sender, receiver, payload, triggerId)
        case m: Message                                     => m
      }
      .foldLeft(Map.empty[StateTransition, StateTransition]) { (acc, message) =>
        val stateTransition = StateTransition(
          userFriendlyActorName(message.sender),
          userFriendlyActorName(message.receiver),
          userFriendlyPayload(message.payload),
        )
        val transition = acc.getOrElse(stateTransition, stateTransition)
        acc.updated(stateTransition, transition.inc())
      }
      .values
      .toIndexedSeq

    val allStates = (transitions.map(_.fromState) ++ transitions.map(_.toState)).toSet
    val stateToShor = allStates.zipWithIndex.map {
      case (state, i) =>
        val onlyLetters = new String(state.toCharArray.filter(_.isLetterOrDigit))
        val shortName = onlyLetters + i
        state -> shortName
    }.toMap

    val descriptions = stateToShor.map {
      case (state, shortName) =>
        StateDescription(state, shortName)
    }.toIndexedSeq
    val convertedTransitions = transitions.map(
      transition =>
        StateTransition(
          fromState = stateToShor(transition.fromState),
          toState = stateToShor(transition.toState),
          message = transition.message,
          number = transition.number
      )
    )
    descriptions ++ convertedTransitions
  }

  sealed trait StateDiagramEntry

  case class StateDescription(longName: String, shortName: String) extends StateDiagramEntry

  case class StateTransition(fromState: String, toState: String, message: String, number: Long = 0)
      extends StateDiagramEntry {
    def inc(): StateTransition = this.copy(number = this.number + 1)
  }

  def serializer: StateDiagramEntry => String = {
    case StateTransition(fromState, toState, message, number) => s"""$fromState --> $toState: $message($number)"""
    case StateDescription(longName, shortName)                => s"""state "$longName" as $shortName"""
  }
}
