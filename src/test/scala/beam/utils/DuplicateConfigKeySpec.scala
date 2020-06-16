package beam.utils
import java.io.{File, PrintWriter}

import org.scalatest.{Matchers, WordSpecLike}

class DuplicateConfigKeySpec extends WordSpecLike with Matchers {

  val dummyConfString: String =
    """
      |beam.agentsim.simulationName = "beamville"
      |beam.agentsim.agentSampleSizeAsFractionOfPopulation = 0.1
      |beam.agentsim.numAgents = 100
      |beam.agentsim.firstIteration = 0
      |beam.agentsim.lastIteration = 2
      |beam.agentsim.simulationName = "sf-light-1k"
      |beam.agentsim.agentSampleSizeAsFractionOfPopulation = 0.2
      |beam.agentsim.simulationName = "sf-light-2k"
      |###########################
      |# Replanning
      |###########################
      |beam.replanning{
      |  maxAgentPlanMemorySize = 4
      |  Module_1 = "SelectExpBeta"
      |  ModuleProbability_1 = 0.7
      |}
      |
    """.stripMargin

  "Duplicate Config Finder" must {
    "count duplicate config keys" in {
      val file = File.createTempFile("somePrefix", ".conf")
      try {
        writeToFile(file, dummyConfString)
        val duplicateKeys = ConfigConsistencyComparator.findDuplicateKeys(file.getPath)
        duplicateKeys should contain("beam.agentsim.simulationName")
        duplicateKeys should contain("beam.agentsim.agentSampleSizeAsFractionOfPopulation")
        duplicateKeys should not contain "beam.agentsim.numAgents"
        duplicateKeys.size should be(2)
      } finally {
        file.delete()
      }
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
