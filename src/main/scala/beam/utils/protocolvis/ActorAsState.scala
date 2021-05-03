package beam.utils.protocolvis

import beam.utils.protocolvis.MessageReader._
import beam.utils.protocolvis.SequenceDiagram.{userFriendlyActorName, userFriendlyPayload}

import java.nio.file.Path
import scala.collection.immutable

/**
  * @author Dmitry Openkov
  */
object ActorAsState {

  def process(messages: Iterator[RowData], output: Path): Unit = {
    PumlWriter.writeData(processAllActors(messages), output)(serializer)
  }

  def processAllActors(messages: Iterator[RowData]): IndexedSeq[StateDiagramEntry] = {
    val transitions = toTransitions(messages)
    toPumlEntries(transitions)
  }

  def processBySingleActor(messages: Iterator[RowData], outputDir: Path): Unit = {
    val transitions = toTransitions(messages)
    val allStates = getAllStates(transitions)
    val singleActorStates: Map[String, IndexedSeq[StateDiagramEntry]] = allStates.map { state =>
      val thisStateTransitions =
        transitions.filter(transition => transition.fromState == state || transition.toState == state)
      val entries = toPumlEntries(thisStateTransitions)
      toShortName(state) -> entries
    }.toMap

    writeToDir(singleActorStates, outputDir)
  }

  private[protocolvis] def writeToDir(
    actorStates: Map[String, IndexedSeq[StateDiagramEntry]],
    outputDir: Path
  ): Unit = {
    actorStates.foreach {
      case (shortName, entries) =>
        PumlWriter.writeData(entries, outputDir.resolve(shortName + ".puml"))(serializer)
    }
  }

  private[protocolvis] def toTransitions(messages: Iterator[RowData]): immutable.IndexedSeq[StateTransition] = {
    messages
      .collect {
        case Event(sender, receiver, payload, _, tick, triggerId) => Message(sender, receiver, payload, tick, triggerId)
        case msg: Message                                         => msg
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

  private[protocolvis] def toPumlEntries(
    transitions: immutable.IndexedSeq[StateTransition]
  ): immutable.IndexedSeq[StateDiagramEntry] = {
    val allStates = getAllStates(transitions)
    val stateToShortName = allStates.map(state => state -> toShortName(state)).toMap

    val descriptions = stateToShortName.map {
      case (state, shortName) =>
        StateDescription(state, shortName)
    }.toIndexedSeq
    val convertedTransitions = transitions.map(
      transition =>
        StateTransition(
          fromState = stateToShortName(transition.fromState),
          toState = stateToShortName(transition.toState),
          message = transition.message,
          number = transition.number
      )
    )
    descriptions ++ convertedTransitions
  }

  private[protocolvis] def toShortName(state: String): String = {
    state.replaceAll("[^A-Za-z0-9]+", "_")
  }

  private def getAllStates(transitions: immutable.IndexedSeq[StateTransition]) =
    (transitions.map(_.fromState) ++ transitions.map(_.toState)).toSet

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
