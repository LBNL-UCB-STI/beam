package beam.utils.scripts.austin_network

import java.io.{File, PrintWriter}

import org.matsim.core.population.io.PopulationReader

import scala.collection.JavaConverters._
import scala.io.Source

object EndTimesFromPlans {

  def main(args: Array[String]): Unit = {
    val bufferedSource = Source.fromFile("E:\\data\\Dropbox\\UCB_LBNL\\austin matsim data ut\\revised_austin_plans.xml")
    var lines = bufferedSource.getLines.toVector
    bufferedSource.close
    //println(lines.size)
    val endTimes = lines.filter(line => line.contains("end_time")).map(line => line.split("end_time=\"")(1).split("\"")(0)).map { time =>
      val split = time.split(":")
      split(0).toInt * 3600 + split(0).toInt * 60 + split(0).toInt * 1
    }


    var pw = new PrintWriter(new File("E:\\data\\Dropbox\\UCB_LBNL\\austin matsim data ut\\endTimes.csv"))
    pw.write(s"activityEndTime\n")
    endTimes.foreach { endTime =>
      pw.write(s"${endTime}\n")
    }

    pw.close


    //println(filtered.size)

  }

}
