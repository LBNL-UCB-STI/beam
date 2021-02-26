package scripts

import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.io.IOUtils

import scala.collection.mutable.ArrayBuffer

object AddExcludedModesUrbansim extends App with LazyLogging {
  val reader = IOUtils.getBufferedReader("/home/crixal/work/projects/beam/production/sfbay/urbansim/2010/baseline/persons.csv.gz")
  val writer = IOUtils.getBufferedWriter("/home/crixal/work/projects/beam/production/sfbay/urbansim/2010/baseline/persons_new.csv.gz")
  var line = reader.readLine()
  writer.write(s"$line,excludedModes")
  line = reader.readLine()
  while(line != null && !line.isEmpty) {
    writer.newLine()
    writer.write(line + ",\"ride_hail_transit,drive_transit,walk_transit,cav,ride_hail,ride_hail_pooled,bus,funicular,gondola,cable_car,ferry,transit,rail,subway,tram,walk,bike\"")
    line = reader.readLine()
  }
  writer.flush()
  writer.close()
}
