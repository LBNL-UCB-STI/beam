package scripts.beam_to_matsim.transit

import scripts.beam_to_matsim.events.BeamPathTraversal

object TransitEventsGroup {

  /**
    * path traversal events of a single vehicle will be grouped based on passenger range
    *
    * @param rangeSize size of passenger range (see `rangeInclusive`)
    * @param events    beam path traversal events of a vehicle
    * @return          group of events fall within same passenger range
    *                  group cannot be empty.
    */
  def groupEvents(
    rangeSize: Int,
    events: Vector[BeamPathTraversal]
  ): Vector[(PassengerRange, Vector[BeamPathTraversal])] = {
    val range = {
      events.headOption
        .map(e => rangeInclusive(e.numberOfPassengers, rangeSize))
        .getOrElse(PassengerRange.Empty)
    }
    val groupedEvents = events.foldLeft(GroupedEvents(range)) { (groupedEvents, event) =>
      val currRange = rangeInclusive(event.numberOfPassengers, rangeSize)
      if (groupedEvents.range == currRange) groupedEvents.add(event)
      else groupedEvents.group(currRange, event)
    }
    groupedEvents.build()
  }

  // helper class to use with foldLeft to build group of events based on passenger
  // state `ranges` tracks each group's range separately from of state `groups`
  private case class GroupedEvents(
    range: PassengerRange,
    acc: Vector[BeamPathTraversal] = Vector(),
    ranges: Vector[PassengerRange] = Vector(),
    groups: Vector[Vector[BeamPathTraversal]] = Vector()
  ) {

    // add event to current accumulator
    def add(event: BeamPathTraversal): GroupedEvents =
      GroupedEvents(range, acc :+ event, ranges, groups)

    // create group of accumulated events and start new accumulator
    def group(newRange: PassengerRange, event: BeamPathTraversal): GroupedEvents =
      GroupedEvents(newRange, Vector(event), ranges :+ range, groups :+ acc)

    // filter out empty groups
    def build(): Vector[(PassengerRange, Vector[BeamPathTraversal])] =
      (ranges :+ range).zip(groups :+ acc).filter(_._2.nonEmpty)
  }

  sealed trait PassengerRange

  object PassengerRange {
    final case object Empty extends PassengerRange
    final case class Range(start: Int, end: Int) extends PassengerRange
  }

  /**
    * if `rangeSize` is zero, range will be same as the passenger count,
    *   if passenger count is 5, its range will be 5:5;
    * if transit is empty, range will be empty
    * for non-empty transit,
    *   if range size is 5 and there are 3 passengers, it fall within 1:5 range,
    *   but 7 passengers will fall within 6:10 range.
    *
    * @param passengersCount  transit passenger count
    * @param rangeSize        bin size
    * @return                 range passenger count falls within
    */
  private def rangeInclusive(passengersCount: Int, rangeSize: Int): PassengerRange = {
    if (rangeSize == 0) PassengerRange.Range(passengersCount, passengersCount)
    else if (passengersCount == 0) PassengerRange.Empty
    else {
      import scala.math.Integral.Implicits._
      passengersCount /% rangeSize match {
        case (quotient, 0) => PassengerRange.Range(((quotient - 1) * rangeSize) + 1, quotient * rangeSize)
        case (quotient, _) => PassengerRange.Range((quotient * rangeSize) + 1, (quotient + 1) * rangeSize)
      }
    }
  }
}
