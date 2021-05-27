package beam.router

import java.io.{File, PrintWriter}
import scala.collection.concurrent.TrieMap
import scala.util.Random
import beam.router.RouteHistory.RouteHistoryADT
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec

class RouteHistorySpec extends AnyFlatSpec with BeforeAndAfter {

  private val routeHistoryAsObject: RouteHistoryADT =
    TrieMap(
      1 -> TrieMap(2 -> TrieMap(3 -> IndexedSeq(4, 5, 6))),
      7 -> TrieMap(8 -> TrieMap(9 -> IndexedSeq(10, 11)))
    )

  private val routeHistoryAsCsvString =
    """timeBin,originLinkId,destLinkId,route
      |1,2,3,4:5:6
      |7,8,9,10:11
      |""".stripMargin

  it should "serialize to a CSV content" in {

    val csvContent = RouteHistory.toCsv(routeHistoryAsObject).mkString

    assert(csvContent === routeHistoryAsCsvString)
  }

  it should "deserialize from a CSV file" in {
    val file = File.createTempFile(Random.alphanumeric.take(10).mkString, ".csv")
    try {
      writeToFile(file, routeHistoryAsCsvString)

      val history = RouteHistory.fromCsv(file.getAbsolutePath)

      assert(history === routeHistoryAsObject)

    } finally {
      file.delete()
    }
  }

  private def writeToFile(file: File, content: String): Unit = {
    val writer = new PrintWriter(file)
    try {
      writer.println(content)
    } finally {
      writer.close()
    }
  }
}
