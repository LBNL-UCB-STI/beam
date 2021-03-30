package beam.utils.protocolvis

import beam.utils.protocolvis.MessageReader.RowData

/**
  * @author Dmitry Openkov
  */
object Extractors {

  def byPerson(personId: String): Function[Iterator[RowData], IndexedSeq[RowData]] = { stream =>
    val (_, seq) = stream.foldLeft(Set.empty[Long] -> IndexedSeq.empty[RowData]) {
      case ((ids, seq), row) =>
        if (isSenderOrReceiver(personId, row)) (if (row.triggerId >= 0) ids + row.triggerId else ids, seq :+ row)
        else if (ids.contains(row.triggerId)) (ids + row.triggerId, seq :+ row)
        else (ids, seq)
    }
    seq
  }

  private def isSenderOrReceiver(personId: String, row: RowData) = {
    row.sender.name == personId || row.receiver.name == personId
  }

  def messageExtractor(extractorType: ExtractorType): Function[Iterator[RowData], IndexedSeq[RowData]] =
    extractorType match {
      case AllMessages  => _.toIndexedSeq
      case ByPerson(id) => byPerson(id)
    }

  sealed trait ExtractorType

  object AllMessages extends ExtractorType

  case class ByPerson(id: String) extends ExtractorType

}
