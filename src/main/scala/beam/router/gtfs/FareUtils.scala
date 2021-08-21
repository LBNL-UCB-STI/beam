package beam.router.gtfs

import beam.utils.FileUtils
import com.typesafe.scalalogging.StrictLogging

import java.io.FileFilter
import java.util.zip.ZipFile

object FareUtils extends StrictLogging {

  /**
    * Checks whether its a valid gtfs feed and has fares data.
    */
  val hasFares: FileFilter = file => {
    var isFareExist = false
    if (file.getName.endsWith(".zip")) {
      try {
        FileUtils.using(new ZipFile(file)) { zip =>
          isFareExist = zip.getEntry("fare_attributes.txt") != null
        }
      } catch {
        case error: Throwable =>
          logger.warn("Failed running hasFares FileFilter", error)
      }
    }
    isFareExist
  }

}
