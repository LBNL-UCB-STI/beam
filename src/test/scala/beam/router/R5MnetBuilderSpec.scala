package beam.router

import java.io.File
import beam.router.r5.{HighwaySetting, R5MnetBuilder}
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.conveyal.r5.kryo.KryoNetworkSerializer
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.network.NetworkWriter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class R5MnetBuilderSpec extends AnyFlatSpec with Matchers {

  it should "build MATSim network from R5 network" in {
    val config = testConfig("test/input/beamville/beam.conf").resolve()
    val r5Dir = new File("test/input/beamville/r5")
    val networkFile = new File(r5Dir, "network.dat")
    val transportNetwork = if (networkFile.exists()) {
      KryoNetworkSerializer.read(networkFile)
    } else {
      TransportNetwork.fromDirectory(r5Dir)
    }
    val builder = new R5MnetBuilder(transportNetwork, BeamConfig(config), HighwaySetting.empty())
    builder.buildMNet()
    val network = builder.getNetwork
    network.getNodes should not be empty
    network.getLinks should not be empty
    FileUtils.usingTemporaryDirectory { tempDir =>
      val tempXml = tempDir.resolve("physsim-network.xml").toFile
      new NetworkWriter(network).write(tempXml.getAbsolutePath)
      tempXml.exists() shouldBe true
    }
  }
  //
  //  it should "not a real test, just for extracting edge data" in {
  ////    val r5Dir = "production/application-sfbay/r5"
  //    val r5Dir = "test/input/sf-light/r5"
  //    var transportNetwork = TransportNetwork.fromDirectory(new File(r5Dir))
  //    transportNetwork.write(new File(s"$r5Dir/network.dat"))
  //    transportNetwork = TransportNetwork.read(new File(s"$r5Dir/network.dat"))
  //    val cursor = transportNetwork.streetLayer.edgeStore.getCursor
  //    val pw = new PrintWriter(new File("bayAreaR5NetworkLinks.txt" ))
  //    pw.write(s"linkId,x,y,lengthInMeters\n")
  //    while(cursor.advance()){
  //      val toVert = transportNetwork.streetLayer.vertexStore.getCursor(cursor.getToVertex)
  //      pw.write(s"${cursor.getEdgeIndex},${toVert.getLon},${toVert.getLat},${cursor.getLengthM}\n")
  //    }
  //    pw.close
  //  }

}
