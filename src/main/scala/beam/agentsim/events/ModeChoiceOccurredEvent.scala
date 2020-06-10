package beam.agentsim.events

import beam.router.model.EmbodiedBeamTrip
import org.matsim.api.core.v01.events.Event

object ModeChoiceOccurredEvent {
  val EVENT_TYPE: String = "ModeChoiceOccurred"

  case class AltUtility(utility: Double, expUtility: Double)
  case class AltCostTimeTransfer(cost: Double, time: Double, numTransfers: Int)

  def apply(
    time: Double,
    personId: String,
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    modeCostTimeTransfers: Map[String, AltCostTimeTransfer],
    alternativesUtility: Map[String, AltUtility],
    chosenAlternativeIdx: Int
  ): ModeChoiceOccurredEvent =
    new ModeChoiceOccurredEvent(
      time,
      personId,
      alternatives,
      modeCostTimeTransfers,
      alternativesUtility,
      chosenAlternativeIdx
    )
}

case class ModeChoiceOccurredEvent(
  time: Double,
  personId: String,
  alternatives: IndexedSeq[EmbodiedBeamTrip],
  modeCostTimeTransfers: Map[String, ModeChoiceOccurredEvent.AltCostTimeTransfer],
  alternativesUtility: Map[String, ModeChoiceOccurredEvent.AltUtility],
  chosenAlternativeIdx: Int
) extends Event(time) {
  import ModeChoiceOccurredEvent._

  override def getEventType: String = EVENT_TYPE
}
