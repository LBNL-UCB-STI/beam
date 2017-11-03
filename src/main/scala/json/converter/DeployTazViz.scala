package json.converter

import java.io.{File, FileNotFoundException, IOException}
import java.util

import scala.collection.JavaConversions._
import net.freeutils.httpserver.HTTPServer


import play.api.libs.json.Json


object DeployTazViz extends App {
  import TazOutput._

  try {
    val trip_stats = "tnc_trip_stats.json"
    val trip_totals = "tnc_taz_totals.json"
    val taz_boundarys = "taz_boundaries.json"

    val rootzip = new java.util.zip.ZipFile(s"${args(0)}/db-files.zip")

    val dd = rootzip.getEntry(trip_stats)
    val totalsTaz = rootzip.getEntry(trip_totals)
    val boundarys = rootzip.getEntry(taz_boundarys)


    val input_f = rootzip.getInputStream(dd)
    val input_total = rootzip.getInputStream(totalsTaz)
    val input_boundary = rootzip.getInputStream(boundarys)

    println("Proccessing JSON....")

    val tazBoundarys = scala.io.Source.fromInputStream(input_boundary)
      .mkString

    val tazStripTotals = Json
      .parse(scala.io.Source.fromInputStream(input_f)
        .mkString)
      .as[Seq[TazStats]]

    val tazStatsByTazDay = tazStripTotals.groupBy( e => (e.taz, e.day_of_week))

    val tazTotals = Json
      .parse(scala.io.Source.fromInputStream(input_total)
        .mkString)
      .as[Seq[TazStatsTotals]]

    println("Finish Proccess...")

    val dir = new File(args(0))
    val port = args(1).toInt
    println("HTTPServer dir " + dir)
    println("HTTPServer port " + port)

    if (!dir.canRead) throw new FileNotFoundException(dir.getAbsolutePath)

    for (f <- util.Arrays.asList(new File("/etc/mime.types"), new File(dir, ".mime.types"))) {
      if (f.exists) HTTPServer.addContentTypes(f)
    }

    val server = new HTTPServer(port)
    val host = server.getVirtualHost(null) // default host
    host.setAllowGeneratedIndex(true) // with directory index pages
    host.addContext("/", new HTTPServer.FileContextHandler(dir))

    host.addContext("/api/tnc_trip_stats", new HTTPServer.ContextHandler() {
      //@throws[IOException]
      override def serve(req: HTTPServer#Request, resp: HTTPServer#Response): Int = {
        resp.getHeaders.add("Content-Type", "application/json")
        if (req.getParams.get("select") == null){
          val params = req.getParams
          val taz = params.get("taz").drop(3).toLong
          val day = params.get("day_of_week").drop(3).toInt

          val response = tazStatsByTazDay.get((taz, day)).map(s => Json.toJson(s).toString()).getOrElse("[]")
          resp.send(200, response)
          0

        }
        else
          {
            val response = tazStripTotals.toString()
            resp.send(200, response)
            0
          }
      }
    })
    host.addContext("/api/tnc_taz_totals", new HTTPServer.ContextHandler() {
      //@throws[IOException]
      override def serve(req: HTTPServer#Request, resp: HTTPServer#Response): Int = {
        resp.getHeaders.add("Content-Type", "application/json")

        val response = Json.toJson(tazTotals).toString()
        resp.send(200, response)
        0
      }
    })

    host.addContext("/api/taz_boundaries", new HTTPServer.ContextHandler() {
      //@throws[IOException]
      override def serve(req: HTTPServer#Request, resp: HTTPServer#Response): Int = {
        resp.getHeaders.add("Content-Type", "application/json")

        val response = tazBoundarys
        resp.send(200, response)
        0
      }
    })
    server.start()
    println("HTTPServer is listening on port " + port)
  }
  catch {
    case e: Exception =>
      println("error: " + e)
  }

  def readJsonFile(f: File): String = {
    val source = scala.io.Source.fromFile(f,"UTF-8")
    val lines = try source.mkString finally source.close()
    lines
  }

}
