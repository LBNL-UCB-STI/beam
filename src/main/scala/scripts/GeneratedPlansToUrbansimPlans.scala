package scripts

import beam.sim.common.GeoUtils
import beam.utils.csv.writers.UrbansimPlansCsvWriter
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import org.matsim.api.core.v01.Coord

object GeneratedPlansToUrbansimPlans {

  def main(args: Array[String]): Unit = {
    println(s"Current arguments: ${args.mkString(",")}")
    if (args.length < 3) {
      println(
        "Expected following arguments: <path to generated plans> <path to output plans csv> <generated plans crs>"
      )
    } else {
      val pathToGeneratedPlans = args(0)
      val pathToOutputPlans = args(1)
      val crsString = args(2)

      object geo extends GeoUtils {
        override def localCRS: String = crsString
      }

      val (plans, closable) = CsvPlanElementReader.readWithFilter(pathToGeneratedPlans, _ => true)

      val plansSelected = plans.filter(p => p.planSelected)
      val plansUTM = plansSelected.map { planElement =>
        (planElement.activityLocationX, planElement.activityLocationY) match {
          case (Some(x), Some(y)) =>
            val wgsLoc = geo.utm2Wgs(new Coord(x, y))
            planElement.copy(activityLocationX = Some(wgsLoc.getX), activityLocationY = Some(wgsLoc.getY))
          case _ => planElement
        }
      }

      UrbansimPlansCsvWriter.toCsvWithHeader(plansUTM, pathToOutputPlans)
      closable.close()
    }
  }
}
