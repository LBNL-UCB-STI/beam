package beam.utils.protocolvis

import beam.utils.protocolvis.MessageReader._
import beam.utils.protocolvis.SequenceDiagram.{userFriendlyActorName, userFriendlyPayload}

/**
  * @author Dmitry Openkov
  */
object ActorAsState {

  def processMessages(messages: Iterator[RowData]): IndexedSeq[StateTransition] = {
    messages
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
  }

  case class StateTransition(fromState: String, toState: String, message: String, number: Long = 0) {
    def inc(): StateTransition = this.copy(number = this.number + 1)
  }

  def serializer: StateTransition => String = {
    case StateTransition(fromState, toState, message, number) => s"""$fromState --> $toState: $message($number)"""
  }
}
