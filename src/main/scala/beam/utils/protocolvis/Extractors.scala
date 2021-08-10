package beam.utils.protocolvis

import beam.utils.protocolvis.MessageReader.RowData

/**
  * @author Dmitry Openkov
  */
object Extractors {

  def byPerson(personId: String): Iterator[RowData] => Iterator[RowData] = { stream =>
    val (_, seq) = stream.foldLeft(Set.empty[Long] -> IndexedSeq.empty[RowData]) { case ((ids, seq), row) =>
      if (isSenderOrReceiver(personId, row)) (if (row.triggerId >= 0) ids + row.triggerId else ids, seq :+ row)
      else if (ids.contains(row.triggerId)) (ids, seq :+ row)
      else (ids, seq)
    }
    seq.iterator
  }

  private def isSenderOrReceiver(personId: String, row: RowData) = {
    row.sender.name == personId || row.receiver.name == personId
  }

  def messageExtractor(extractorType: ExtractorType): Function[Iterator[RowData], Iterator[RowData]] =
    extractorType match {
      case AllMessages  => identity
      case ByPerson(id) => byPerson(id)
    }

  sealed trait ExtractorType

  object AllMessages extends ExtractorType

  case class ByPerson(id: String) extends ExtractorType

}
