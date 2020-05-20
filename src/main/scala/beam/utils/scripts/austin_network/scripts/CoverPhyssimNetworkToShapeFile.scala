package beam.utils.scripts.austin_network.scripts

import beam.utils.scripts.austin_network.AustinUtils

object CoverPhyssimNetworkToShapeFile {
  def main(args: Array[String]): Unit = {
    AustinUtils.writePhyssimToShapeFile("E:\\work\\random\\uber speeds\\physsim-network.xml","E:\\work\\random\\uber speeds\\physsim-network.shp",10.0)
  }
}
