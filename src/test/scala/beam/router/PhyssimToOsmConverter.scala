package beam.router

import java.nio.file.Paths

object PhyssimToOsmConverter {

  def main(args: Array[String]): Unit = {
    val sourcePhyssimNetwork = Paths.get(args(0))
    val targetOsm = Paths.get(args(1))

    val converter = new NetworkToOsmConverterFromFile(sourcePhyssimNetwork)
    val network = converter.build()
    network.writeToFile(targetOsm)
  }

}
