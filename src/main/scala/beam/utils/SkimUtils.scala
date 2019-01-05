package beam.utils
import beam.agentsim.agents.planning.BeamPlan
import org.matsim.api.core.v01.{Coord, Scenario}
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.facilities.ActivityFacility

import scala.collection.immutable.{List, Map}

object SkimUtils {

  def calcCoordToCoordSkim(plans: List[BeamPlan]): Map[Coord, Map[Coord, Double]] = {
    var skim = Map[Coord, Map[Coord, Double]]()
    var activitySet = Set[Coord]()
    for (plan <- plans) {
      for (act <- plan.activities) {
        activitySet += act.getCoord
      }
    }
    for (actSrc <- activitySet) {
      skim = skim + (actSrc -> Map[Coord, Double]())
      for (actDst <- activitySet) {
        val travelTime: Double = beam.sim.common.GeoUtils.distFormula(actSrc, actDst) * 60
        skim = skim + (actSrc -> (skim(actSrc) ++ Map(actDst -> travelTime)))
      }
    }
    skim
  }

  def calcFacilityToFacilitySkim(sc : Scenario, plans: List[BeamPlan]): Map[ActivityFacility, Map[ActivityFacility, Double]] = {
    var skim = Map[ActivityFacility, Map[ActivityFacility, Double]]()
    var facilitiesSet = Set[ActivityFacility]()
    for (plan <- plans) {
      for (act <- plan.activities) {
        facilitiesSet += sc.getActivityFacilities.getFacilities.get(act.getFacilityId)
      }
    }
    for (fSrc <- facilitiesSet) {
      skim = skim + (fSrc -> Map[ActivityFacility, Double]())
      for (fDst <- facilitiesSet) {
        val travelTime: Double = beam.sim.common.GeoUtils.distFormula(fSrc.getCoord, fDst.getCoord) * 60
        skim = skim + (fSrc -> (skim(fSrc) ++ Map(fDst -> travelTime)))
      }
    }
    skim
  }

}
