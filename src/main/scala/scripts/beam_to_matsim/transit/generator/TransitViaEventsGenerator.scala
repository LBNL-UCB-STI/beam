package scripts.beam_to_matsim.transit.generator

import scripts.beam_to_matsim.events.BeamPathTraversal
import scripts.beam_to_matsim.via_event.ViaEvent

object TransitViaEventsGenerator {
  type EventsGenerator = Vector[BeamPathTraversal] => Vector[ViaEvent]

  def commonViaEventsGeneratorWithLinks(idleThreshold: Double, numPassengersSplit: Int): EventsGenerator =
    CommonViaEventsGeneratorWithLinks.generator(idleThreshold, numPassengersSplit)

  def ridehailViaEventsGenerator(idleThreshold: Double, numPassengersSplit: Int): EventsGenerator =
    RidehailViaEventsGenerator.generator(idleThreshold, numPassengersSplit)

  def commonViaEventsGeneratorWithoutLinks(
    idleThreshold: Double,
    numPassengersSplit: Int,
    networkPath: String
  ): EventsGenerator =
    CommonViaEventsGeneratorWithoutLinks(networkPath).generator(idleThreshold, numPassengersSplit)
}

private[transit] trait TransitViaEventsGenerator {
  def generator(idleThresholdInSec: Double, rangeSize: Int)(events: Vector[BeamPathTraversal]): Vector[ViaEvent]
}
