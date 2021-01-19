package beam.router

import java.nio.file.Paths

object PhyssimToOsmConverter extends App {
  private val physymNetworkPath = "/Users/ccaldas/carloscaldas/src/src-beam/beam/test/input/beamville/osm-converter/output_network.xml"
  private val path = Paths.get(physymNetworkPath)
  val converter = new NetworkToOsmConverterFromFile(path)
  val network = converter.build()
  val outputOsm = Paths.get("/Users/ccaldas/carloscaldas/src/src-beam/beam/test/input/beamville/osm-converter/output_network_as_osm.osm")
  network.writeToFile(outputOsm)
}
