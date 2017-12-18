package beam.router

import java.io.File

import beam.router.r5.R5MnetBuilder
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.network.NetworkWriter
import org.scalatest.FlatSpec

class R5MnetBuilderSpec extends FlatSpec {

  it should "do something" in {
    var transportNetwork = TransportNetwork.fromDirectory(new File("test/input/beamville/r5"))
    val cursor = transportNetwork.streetLayer.edgeStore.getCursor
    transportNetwork.write(new File("test/input/beamville/r5/network.dat"))
    transportNetwork = TransportNetwork.read(new File("test/input/beamville/r5/network.dat"))
    val builder = new R5MnetBuilder(transportNetwork, "test/input/beamville/r5/osm.mapdb")
    builder.buildMNet()
    val network = builder.getNetwork
    new NetworkWriter(network).write("test/input/beamville/physsim-network.xml")
  }

}
