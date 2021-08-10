package beam.utils.protocolvis

import beam.utils.protocolvis.ActorAsState.{toPumlEntries, toShortName, writeToDir, StateTransition}
import beam.utils.protocolvis.MessageReader.{RowData, Transition}
import beam.utils.protocolvis.SequenceDiagram.userFriendlyActorName

import java.nio.file.Path

/**
  * @author Dmitry Openkov
  */
object AgentStateDiagram {

  def process(messages: Iterator[RowData], outputDir: Path): Unit = {
    val transitions = toTransitions(messages)
    val singleActorStates = transitions.map { case (actor, actorTransitions) =>
      val entries = toPumlEntries(actorTransitions.values.toIndexedSeq)
      toShortName(actor) -> entries
    }
    writeToDir(singleActorStates, outputDir)
  }

  private[protocolvis] def toTransitions(
    messages: Iterator[RowData]
  ): Map[String, Map[StateTransition, StateTransition]] = {
    messages
      .collect { case transition: Transition =>
        transition
      }
      .foldLeft(Map.empty[String, Map[StateTransition, StateTransition]]) { (acc, transition) =>
        val actor = userFriendlyActorName(transition.receiver)
        val stateTransition = StateTransition(
          transition.prevState,
          transition.state,
          ""
        )
        val actorMap = acc.getOrElse(actor, Map.empty)
        val transitions = actorMap.getOrElse(stateTransition, stateTransition)
        val newTransitions = actorMap.updated(stateTransition, transitions.inc())
        acc.updated(actor, newTransitions)
      }
  }

}
