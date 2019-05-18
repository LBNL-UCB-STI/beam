package beam.utils

import beam.utils.csv.conversion.NetworkXmlToCSV
import org.scalatest.WordSpecLike

class NetworkXmlToCsvSpec extends WordSpecLike {

  "networkXmlToCsv class " in {
    val path = "test/input/beamville/physsim-network.xml"
    val nodeOutput = "test/input/beamville/node-network.csv"
    val linkOutput = "test/input/beamville/link-network.csv"
    val mergeOutput = "test/input/beamville/merge-network.csv"
    val delimiter = "\t"
    NetworkXmlToCSV.networkXmlParser(path, delimiter, nodeOutput, linkOutput, mergeOutput)
  }
}
