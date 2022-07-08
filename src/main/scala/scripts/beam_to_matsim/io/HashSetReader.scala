package beam.utils.beam_to_matsim.io

import scala.collection.mutable

object HashSetReader {

  def fromFile(filePath: String): mutable.HashSet[String] = {
    val ids = mutable.HashSet.empty[String]

    import scala.io.Source
    val source = Source fromFile filePath
    source.getLines().foreach(ids += _)
    source.close()

    Console.println("read " + ids.size + " ids from " + filePath)
    ids
  }
}
