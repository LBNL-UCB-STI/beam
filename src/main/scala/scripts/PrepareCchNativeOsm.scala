package scripts

import beam.utils.FileUtils
import com.conveyal.osmlib.{OSM, OSMEntity}
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.io.IOUtils

import java.io.{File, FileInputStream}
import java.nio.file.Paths


object PrepareCchNativeOsm extends App with LazyLogging {
  val fileStr = "/home/crixal/work/projects/beam/test/input/sf-light/r5/sflight_muni.osm.pbf"
  val transportNetwork = TransportNetwork.fromDirectory(Paths.get(fileStr).getParent.toFile, true, false)
  val timeStampStr = "2021-01-21T15:41:30Z"
  val osm = new OSM(null)
  osm.readPbf(new FileInputStream(new File(fileStr)))

  FileUtils.using(IOUtils.getBufferedWriter("/home/crixal/work/sf-light.osm")) { bw =>
    bw.write("<?xml version='1.0' encoding='UTF-8'?>")
    bw.newLine()
    bw.write("<osm version=\"0.6\" generator=\"BEAM\">")
    bw.newLine()

    val vertexCur = transportNetwork.streetLayer.vertexStore.getCursor
    for (idx <- 0 until transportNetwork.streetLayer.vertexStore.getVertexCount) {
      vertexCur.seek(idx)
      val nodeStr = "  <node id=\"" + idx + "\" timestamp=\"" + timeStampStr + "\" lat=\"" + vertexCur.getLat.toString + "\" lon=\"" + vertexCur.getLon.toString + "\" uid=\"1\" version=\"1\" changeset=\"0\"/>"
      bw.write(nodeStr)
      bw.newLine()
    }

    val cur = transportNetwork.streetLayer.edgeStore.getCursor
    for (idx <- 0 until transportNetwork.streetLayer.edgeStore.nEdges by 1) {
      cur.seek(idx)

      if (cur.allowsStreetMode(StreetMode.CAR)) {

        bw.write("  <way id=\"" + idx + "\" timestamp=\"" + timeStampStr + "\" uid=\"1\" user=\"beam\" version=\"1\" changeset=\"0\">")
        bw.newLine()
        bw.write("    <nd ref=\"" + cur.getFromVertex.toString + "\"/>")
        bw.newLine()
        bw.write("    <nd ref=\"" + cur.getToVertex.toString + "\"/>")
        bw.newLine()

        val osmWay = osm.ways.get(cur.getOSMID)
        if (osmWay != null) {
          osmWay.tags.forEach((tag: OSMEntity.Tag) => {
            if (tag.key == "oneway" && (tag.value == "no" || tag.value == "false" || tag.value == "0")) {
              bw.write("    <tag k=\"" + tag.key + "\" v=\"yes\"/>")
            } else {
              bw.write("    <tag k=\"" + tag.key + "\" v=\"" + escapeHTML(tag.value) + "\"/>")
            }
            bw.newLine()
          })
        } else {
          bw.write("    <tag k=\"highway\" v=\"trunk\"/>")
          bw.write("    <tag k=\"oneway\" v=\"yes\"/>")
          bw.newLine()
        }

        bw.write("  </way>")
        bw.newLine()
      }
    }

    bw.write("</osm>")
    bw.flush()
  }

  def escapeHTML(s: String): String = {
    val out = new StringBuilder(Math.max(16, s.length))
    for (i <- 0 until s.length) {
      val c = s.charAt(i)
      if (c > 127 || c == '"' || c == '\'' || c == '<' || c == '>' || c == '&') {
        out.append("&#")
        out.append(c.toInt)
        out.append(';')
      }
      else out.append(c)
    }
    out.toString
  }
}
