package beam.router

import java.io.{File, PrintWriter}

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamSkimmer.{BeamSkimmerADT, BeamSkimmerKey, SkimInternal}
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Id
import org.scalatest.{BeforeAndAfter, FlatSpec}

import scala.collection.concurrent.TrieMap
import scala.util.Random

class BeamSkimmerSpec extends FlatSpec with BeforeAndAfter {

  val beamSkimmerAsObject: BeamSkimmerADT = TrieMap(
    (23, BeamMode.CAR, Id.create(2, classOf[TAZ]), Id.create(1, classOf[TAZ])) -> SkimInternal(
      time = 205.0,
      generalizedTime = 215.0,
      cost = 6.491215096413,
      generalizedCost = 6.968992874190778,
      distance = 4478.644999999999,
      count = 1,
      energy = 1.4275908092571782E7
    ),
    (7, BeamMode.WALK_TRANSIT, Id.create(1, classOf[TAZ]), Id.create(1, classOf[TAZ])) -> SkimInternal(
      time = 90.0,
      generalizedTime = 108.99999999999999,
      cost = 0.0,
      generalizedCost = 1.232222222222222,
      distance = 1166.869,
      count = 1,
      energy = 2908432.6946756938
    )
  )

  private val beamSkimmerAsCsv =
    """hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy
      |23,CAR,2,1,205.0,215.0,6.491215096413,6.968992874190778,4478.644999999999,1,1.4275908092571782E7
      |7,WALK_TRANSIT,1,1,90.0,108.99999999999999,0.0,1.232222222222222,1166.869,1,2908432.6946756938
      |""".stripMargin

  it should "serialize not empty map to CSV" in {
    val csvContent = BeamSkimmer.toCsv(beamSkimmerAsObject).mkString

    assert(csvContent === beamSkimmerAsCsv)
  }

  it should "serialize empty map to CSV" in {
    val emptyMap = TrieMap.empty[BeamSkimmerKey, SkimInternal]

    val csvContent = BeamSkimmer.toCsv(emptyMap).mkString

    assert(csvContent === BeamSkimmer.CsvLineHeader)
  }

  it should "deserialize from a CSV file" in {
    val file = File.createTempFile(Random.alphanumeric.take(10).mkString, ".csv")
    try {
      writeToFile(file, beamSkimmerAsCsv)

      val history: BeamSkimmerADT = BeamSkimmer.fromCsv(file.getAbsolutePath)

      assert(history === beamSkimmerAsObject)

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
