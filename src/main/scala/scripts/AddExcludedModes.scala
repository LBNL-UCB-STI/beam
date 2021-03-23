package scripts
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.io.IOUtils

import scala.collection.mutable.ArrayBuffer

object AddExcludedModes extends App with LazyLogging {
  val reader = IOUtils.getBufferedReader("/home/crixal/work/projects/beam/production/sfbay/beam_scenario/normal/population.csv.gz")
  val writer = IOUtils.getBufferedWriter("/home/crixal/work/projects/beam/production/sfbay/beam_scenario/only_car_and_walk/population.csv.gz")
  var line = reader.readLine()
  writer.write(line)
  line = reader.readLine()
  while(line != null && !line.isEmpty) {
    writer.newLine()
    val parts = line.split(",")
    val strs = new ArrayBuffer[String]()
    var i=0
    while(i<parts.length) {
      if(i == 5) {
        strs += "\"ride_hail_transit,drive_transit,walk_transit,bike_transit,cav,ride_hail,ride_hail_pooled,bus,funicular,gondola,cable_car,ferry,transit,rail,subway,tram,walk,bike\""
      } else {
        strs += parts(i)
      }
      i += 1
    }
    writer.write(strs.mkString(","))
    line = reader.readLine()
  }
  writer.flush()
  writer.close()
}