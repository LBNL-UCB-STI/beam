package beam.utils

import beam.agentsim.infrastructure.taz
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import org.matsim.core.utils.io.IOUtils
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

object GpxCompareSimulationVsReference {

  def main(args: Array[String]): Unit = {
    val outWriter = IOUtils.getBufferedWriter("gpx.xml")
    outWriter.write(
      """<?xml version="1.0" encoding="UTF-8" standalone="no" ?>
        |<gpx version="1.1" creator="http://www.geoplaner.com" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://www.topografix.com/GPX/1/1" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""".stripMargin
    )

    if (args.length != 3)
      System.exit(0)

    val config = BeamConfigUtils
      .parseFileSubstitutingInputDirectory(args(0))
      .resolve()
    val tazODTravelTimeObservedVsSimulated = args(1)
    val minimumTime = args(2).toInt

    try {
      val beamConfig = BeamConfig(config)
      val geo: beam.sim.common.GeoUtils = new GeoUtilsImpl(beamConfig)

      val tazTreeMap: TAZTreeMap =
        taz.TAZTreeMap.getTazTreeMap(beamConfig.beam.agentsim.taz.filePath)

      val mapReader: ICsvMapReader =
        new CsvMapReader(
          FileUtils.readerFromFile(tazODTravelTimeObservedVsSimulated),
          CsvPreference.STANDARD_PREFERENCE
        )
      try {
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          val sourceid = tazTreeMap.getTAZ(line.get("fromTAZId"))
          val dstid = tazTreeMap.getTAZ(line.get("toTAZId"))
          val timeSimulated = line.get("timeSimulated").toInt

          if (timeSimulated >= minimumTime)
            sourceid.foreach(source => {
              dstid.foreach { destination =>
                {
                  outWriter.write("<rte>")
                  outWriter.newLine()
                  outWriter.write(s"""<rtept lon="${geo.utm2Wgs(source.coord).getX}" lat="${geo
                    .utm2Wgs(source.coord)
                    .getY}"><name>Source</name></rtept>""")
                  outWriter.newLine()
                  outWriter.write(s"""<rtept lon="${geo.utm2Wgs(destination.coord).getX}" lat="${geo
                    .utm2Wgs(destination.coord)
                    .getY}"><name>Destination</name></rtept>""")
                  outWriter.newLine()
                  outWriter.write(s"""<name>${line.get("hour")}, ${line.get("timeSimulated")}</name>""")
                  outWriter.newLine()
                  outWriter.write("</rte>")
                  outWriter.newLine()
                }
              }
            })

          line = mapReader.read(header: _*)
        }
      } finally {
        if (null != mapReader)
          mapReader.close()
      }

    } finally {
      outWriter.write("</gpx>")

      outWriter.flush()
      outWriter.close()
    }
  }
}
