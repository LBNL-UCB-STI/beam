package beam.utils

import java.nio.file.Paths

import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.immutable.Map

class BeamConfigUtilsTest extends WordSpecLike with Matchers {

  def getCollector(map: Map[String, Array[String]]): BeamConfigUtils.ConfigPathsCollector =
    new BeamConfigUtils.ConfigPathsCollector((path: String) => {
      map.get(path) match {
        case Some(lines) => lines
        case None        => Array.empty[String]
      }
    })

  def toPathStr(strPath: String): String = Paths.get(strPath).toString
  private val mockText = Array(
    "# This version, base-sf-light.conf, is configured to use a subsample of the population located in:",
    "#  ${beam.inputDirectory}\"/sample\"",
    "##################################################################",
    "# Agentsim",
    "##################################################################",
    "beam.agentsim.simulationName = \"sf-light-1k-xml\"",
    "beam.agentsim.agentSampleSizeAsFractionOfPopulation = 1.0",
    "beam.agentsim.firstIteration = 0",
    "beam.agentsim.lastIteration = 20",
    "beam.agentsim.thresholdForWalkingInMeters = 100",
    "beam.agentsim.timeBinSize = 3600",
    "beam.agentsim.startTime = \"00:00:00\"",
    "beam.agentsim.endTime = \"30:00:00\"",
    "beam.agentsim.schedulerParallelismWindow = 30"
  )

  "collector of included conf file paths " should {
    "return only one path if there are no includes " in {
      val filePath = "test/test2/ttt/file0.f"
      val collector = getCollector(Map(filePath -> mockText))
      collector.getFileNameToPath(filePath) should equal(
        Map("beam.conf" -> toPathStr(filePath))
      )
    }

    "return all path from includes " in {
      val filesPaths =
        Array("test/test2/supertest/file0", "test/test2/ttt/file1.fff", "test/test2/file2.f", "test/test2/ttt/file3.f")
          .map(toPathStr)
      val fileIncludedPaths = Array("../supertest/file0", "../file2.f", "file3.f")
      val fileMap = Map(
        filesPaths(1) -> Array.concat(
          Array(
            " include \"" + fileIncludedPaths(0) + "\"",
            "some random text",
            "include    \"" + fileIncludedPaths(1) + "\" some text",
            "include\"" + fileIncludedPaths(2) + "\""
          ),
          mockText
        ),
        filesPaths(0) -> mockText,
        filesPaths(2) -> mockText,
        filesPaths(3) -> mockText
      )

      val collector = getCollector(fileMap)
      collector.getFileNameToPath(filesPaths(1)) should equal(
        Map(
          "beam.conf" -> filesPaths(1),
          "file0"     -> filesPaths(0),
          "file2.f"   -> filesPaths(2),
          "file3.f"   -> filesPaths(3)
        )
      )
    }

    "correctly name files if they have same names " in {
      val filesPaths = Array(
        "test/test2/supertest/file0.ff",
        "test/test2/ttt/file1.fff",
        "test/test2/file0.ff",
        "test/test2/ttt/file0.ff"
      ).map(toPathStr)

      val fileMap = Map(
        filesPaths(1) -> Array.concat(
          Array(
            " include \"../supertest/file0.ff\"",
            "some random text",
            "include    \"../file0.ff\" some text",
            "include\"file0.ff\""
          ),
          mockText
        ),
        filesPaths(0) -> mockText,
        filesPaths(2) -> mockText,
        filesPaths(3) -> mockText
      )

      val collector = getCollector(fileMap)
      val f2p = collector.getFileNameToPath(filesPaths(1))

      f2p.keys.toSet should equal(Set("file0.ff", "file0_1.ff", "beam.conf", "file0_2.ff"))
      f2p.values.toSet should equal(Set(filesPaths(0), filesPaths(1), filesPaths(2), filesPaths(3)))
    }

    "collect all includes recursively " in {
      val filesPaths =
        Array("test/test2/supertest/file0.ff", "test/test2/file2.fff", "test/test2/ttt/file3.f3f3").map(toPathStr)
      val filePath = toPathStr("test/test2/ttt/file1.fff")
      val fileMap = Map(
        filePath      -> (" include \"../supertest/file0.ff\"" +: mockText),
        filesPaths(0) -> (" include \"../file2.fff\"" +: mockText),
        filesPaths(1) -> (" include \"ttt/file3.f3f3\"" +: mockText),
        filesPaths(2) -> mockText
      )

      val collector = getCollector(fileMap)
      collector.getFileNameToPath(filePath) should equal(
        Map(
          "beam.conf"  -> filePath,
          "file0.ff"   -> filesPaths(0),
          "file2.fff"  -> filesPaths(1),
          "file3.f3f3" -> filesPaths(2)
        )
      )
    }

    "ignore files if they appears more than one time in includes " in {
      val filesPaths = Array(
        "test/test2/supertest/file0.ff",
        "test/test2/ttt/file1.fff",
        "test/test2/file2.fff",
        "test/test2/ttt/file3.f3f3"
      ).map(toPathStr)

      val fileMap = Map(
        filesPaths(1) -> Array.concat(Array(" include \"../supertest/file0.ff\"", " include \"file3.f3f3\""), mockText),
        filesPaths(0) -> Array.concat(mockText, Array("include \"../file2.fff\" ", " include \"../ttt/file3.f3f3\" ")),
        filesPaths(2) -> Array.concat(mockText, Array("include \"ttt/file1.fff\"", "include \"supertest/file0.ff\"")),
        filesPaths(3) -> Array.concat(mockText, Array("include\"file1.fff\"", "include\"../supertest/file0.ff\""))
      )

      val collector = getCollector(fileMap)
      collector.getFileNameToPath(filesPaths(1)) should equal(
        Map(
          "beam.conf"  -> filesPaths(1),
          "file0.ff"   -> filesPaths(0),
          "file2.fff"  -> filesPaths(2),
          "file3.f3f3" -> filesPaths(3)
        )
      )
    }
  }
}
