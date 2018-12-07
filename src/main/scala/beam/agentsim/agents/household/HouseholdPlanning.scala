package beam.agentsim.agents.household
import beam.agentsim.agents.planning.BeamPlan


import util.control.Breaks._
import scala.collection.mutable.ArrayBuffer
import org.matsim.api.core.v01.population._
import scala.collection.mutable.Map
import scala.collection.immutable.TreeSet

abstract class MobilityServiceRequest extends Ordered[MobilityServiceRequest] {
  def person : Option[Person]
  def activity : Option[Activity]
  def time : Double
  def delta_time : Double
  def getRequestTime() : Unit = { return(time) }
  def getServiceTime() : Unit = { return(time+delta_time) }
  def compare( that: MobilityServiceRequest) = { Ordering.Double.compare(this.time, that.time)}
}
// Mobility Service Request is either a pickup or a dropoff
class MSRPickup (val person : Option[Person],
                 val activity : Option[Activity],
                 val time : Double,
                 val delta_time : Double) extends MobilityServiceRequest
class MSRDropoff (val person : Option[Person],
                  val activity : Option[Activity],
                  val time : Double,
                  val delta_time : Double) extends MobilityServiceRequest

class HouseholdPlansToMSR(plans : ArrayBuffer[BeamPlan]) {
  var requests = new TreeSet[MobilityServiceRequest]()
  for( plan <- plans) {
    for (activity <- plan.activities) {
      if (activity.getStartTime != Double.NaN)
        requests = requests + new MSRDropoff(Some(plan.getPerson), Some(activity), activity.getStartTime, 0.0)
      if (activity.getEndTime != Double.NaN)
        requests = requests + new MSRPickup(Some(plan.getPerson), Some(activity), activity.getEndTime, 0.0)
    }
  }
  def apply() : Boolean = { !requests.isEmpty }
  def next(): MobilityServiceRequest = {
    val next_request = requests.head
    requests=requests-next_request
    next_request
  }
}



class HouseholdCAVPlanning(val plans : ArrayBuffer[BeamPlan],
                           val time_window : Double,
                           val skim : Map[Activity, Map[Activity, Double]]) {

  val fleet = ArrayBuffer[HouseholdCAV](HouseholdCAV(4), HouseholdCAV(4)) //TODO to be extracted from Attr
  var feasible_schedules = new TreeSet[HouseholdCAVSchedule];
  /*
  * schedule = TreeSet[MobilityServiceRequest](new MobilityServiceRequest {
      def person: Option[Person] = None
      def activity : Option[Activity] = Some(home_activity)
      def time : Double = home_activity.getEndTime-1 // -1 to be ranked before endtime
      def delta_time : Double = 1 // recuperate the subtracted second
    });
  * */

  case class HouseholdCAV(val max_occupancy: Int)

  case class CAVSchedule(val schedule : TreeSet[MobilityServiceRequest],
                         val cost: Double,
                         val occupancy: Int,
                         val cav : HouseholdCAV) {
    def check(request : MobilityServiceRequest): Option[CAVSchedule] = {
      val travel_time = skim(schedule.last.activity)(request.activity.get)
      val arrival_time = schedule.last.time + schedule.last.delta_time + travel_time
      val new_delta_time = arrival_time - request.time
      var new_cav_schedule : Option[CAVSchedule] = None
      if(request.isInstanceOf[MSRPickup] && occupancy < cav.max_occupancy && math.abs(new_delta_time) <= time_window) {
        val new_request = new MSRPickup(request.person, request.activity, request.time, new_delta_time)
        new_cav_schedule = Some(CAVSchedule(schedule+new_request,cost+new_delta_time,occupancy+1,cav))
      } else if(request.isInstanceOf[MSRDropoff] && occupancy > 0 && math.abs(new_delta_time) <= time_window) {
        val new_request = new MSRDropoff(request.person, request.activity, request.time, new_delta_time)
        new_cav_schedule = Some(CAVSchedule(schedule+new_request,cost+new_delta_time,occupancy-1,cav))
      }
      new_cav_schedule
    }
  }

  case class HouseholdCAVSchedule(val cav_fleet_schedule : ArrayBuffer[CAVSchedule],
                                  val cost : Double) extends Ordered[HouseholdCAVSchedule] {
    override def compare(that: HouseholdCAVSchedule): Int = { Ordering.Double.compare(this.cost, that.cost) }
    def check(request : MobilityServiceRequest): ArrayBuffer[HouseholdCAVSchedule] = {
      val new_household_schedule = new ArrayBuffer[HouseholdCAVSchedule]()
      for(cav_schedule <- cav_fleet_schedule) {
        cav_schedule.check(request) match {
          case Some(x) => {
            new_household_schedule += HouseholdCAVSchedule(
              ArrayBuffer[CAVSchedule](x)++=(cav_fleet_schedule-cav_schedule),
              cost+x.cost)
          }
        }
      }
      new_household_schedule
    }
  }

  def buildFeasibleSchedules() : Unit = {
    val household_requests = new HouseholdPlansToMSR(plans); // potential CAV requests from household plans
    while(household_requests()) {
      val request : MobilityServiceRequest = household_requests.next()
      var new_household_schedules = new ArrayBuffer[HouseholdCAVSchedule]
      for(schedule <- feasible_schedules){
        new_household_schedules ++= schedule.check(request)
      }
      feasible_schedules = feasible_schedules ++ new_household_schedules
    }
  }







}

object Demo {

  def main(args: Array[String]): Unit = {


  }
}
