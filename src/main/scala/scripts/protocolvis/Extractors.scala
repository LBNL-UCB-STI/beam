package scripts.protocolvis

import scripts.protocolvis.MessageReader.RowData

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

  def byTrigger(triggerId: Long): Iterator[RowData] => Iterator[RowData] = _.filter(_.triggerId == triggerId)

  private def isSenderOrReceiver(personId: String, row: RowData) = {
    row.sender.name == personId || row.receiver.name == personId
  }

  def messageExtractor(extractorType: ExtractorType): Function[Iterator[RowData], Iterator[RowData]] =
    extractorType match {
      case AllMessages   => identity
      case ByPerson(id)  => byPerson(id)
      case ByTrigger(id) => byTrigger(id)
    }

  sealed trait ExtractorType

  object AllMessages extends ExtractorType

  case class ByPerson(id: String) extends ExtractorType
  case class ByTrigger(id: Long) extends ExtractorType

}
