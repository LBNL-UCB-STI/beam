package beam.utils.beamToVia

import beam.utils.EventReader
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamEventReader}
import beam.utils.beamToVia.beamEventsFilter.MutableSamplingFilter
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable

object BeamEventsReader {

  def fromFileFoldLeft[T](filePath: String, accumulator: T, foldLeft: (T, BeamEvent) => T): Option[T] = {
    Console.println("started reading a file " + filePath + " for folding")

    val extension = filePath.split('.').lastOption
    val result = extension match {
      case Some("xml") =>
        Some(
          EventReader
            .fromXmlFile(filePath)
            .foldLeft(accumulator)((acc, event) => {
              BeamEventReader.read(event) match {
                case Some(beamEvent) => foldLeft(acc, beamEvent)
                case _               => acc
              }
            })
        )
      case Some("csv") => Some(fromCsvFold(filePath, accumulator, foldLeft))
      case _           => None
    }

    result
  }

  private def fromCsvFold[T](filePath: String, accumulator: T, foldLeft: (T, BeamEvent) => T): T = {
    val (events, closable) = EventReader.fromCsvFile(filePath, _ => true)
    val result = events.foldLeft(accumulator)((acc, event) => {
      BeamEventReader.read(event) match {
        case Some(beamEvent) => foldLeft(acc, beamEvent)
        case _               => acc
      }
    })

    closable.close()
    result
  }
}
