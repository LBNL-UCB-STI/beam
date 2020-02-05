package census.db.creator.service.actors

import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.file.Paths
import java.util.zip.ZipInputStream

import akka.actor.{Actor, ActorRef, ActorSystem}
import census.db.creator.config.Config

import scala.concurrent.ExecutionContext

case class UnzipMessage(path: String)

class UnzipActor(config: Config, shapeReader: ActorRef)(private implicit val executionContext: ExecutionContext)
    extends Actor {
  private implicit val actorSystem: ActorSystem = context.system

  override def receive: Receive = {
    case UnzipMessage(path) =>
      val fileName = path.split("/").last
      val shapefilesDir = "shapes"

      val resultDir = Paths.get(config.workingDir, shapefilesDir).toString
      var shapeFile: String = ""

      val zis = new ZipInputStream(new FileInputStream(path))
      Stream.continually(zis.getNextEntry).takeWhile(_ != null).foreach { file =>
        val fileName = Paths.get(resultDir, file.getName).toString
        if (fileName.endsWith(".shp"))
          shapeFile = fileName
        new File(fileName).delete()
        val fout = new FileOutputStream(fileName)
        val buffer = new Array[Byte](1024)
        Stream.continually(zis.read(buffer)).takeWhile(_ != -1).foreach(fout.write(buffer, 0, _))
        zis.closeEntry()
      }
      zis.close()
      if (shapeFile.isEmpty) throw new RuntimeException(s"no shapefile! for file $path")

      println(s"Unzipped $shapeFile")

      shapeReader ! ShapeFileMessage(shapeFile)
  }

}
