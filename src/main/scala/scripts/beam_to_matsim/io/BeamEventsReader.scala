package beam.utils.beam_to_matsim.io

import beam.utils.EventReader
import beam.utils.beam_to_matsim.events.{BeamEvent, BeamEventReader}

object BeamEventsReader {

  def createProgressObject(filePath: String): ConsoleProgress = {
    val src = scala.io.Source.fromFile(filePath)
    val fileLength = src.getLines.size
    src.close()

    new ConsoleProgress("events from file analyzed", fileLength, 17)
  }

  def fromFileFoldLeft[T](filePath: String, accumulator: T, foldLeft: (T, BeamEvent) => T): Option[T] = {
    val extension = filePath.split('.').lastOption

    val result = extension match {
      case Some("xml") =>
        val progress = createProgressObject(filePath)
        val events = Some(
          EventReader
            .fromXmlFile(filePath)
            .foldLeft(accumulator)((acc, event) => {
              progress.step()
              BeamEventReader.read(event) match {
                case Some(beamEvent) => foldLeft(acc, beamEvent)
                case _               => acc
              }
            })
        )
        progress.finish()
        events

      case Some("csv") => Some(fromCsvFold(filePath, accumulator, foldLeft))
      case _           => None
    }

    result
  }

  private def fromCsvFold[T](filePath: String, accumulator: T, foldLeft: (T, BeamEvent) => T): T = {
    val progress = createProgressObject(filePath)
    val (events, closable) = EventReader.fromCsvFile(filePath, _ => true)
    val result = events.foldLeft(accumulator)((acc, event) => {
      progress.step()
      BeamEventReader.read(event) match {
        case Some(beamEvent) => foldLeft(acc, beamEvent)
        case _               => acc
      }
    })

    progress.finish()
    closable.close()
    result
  }
}
