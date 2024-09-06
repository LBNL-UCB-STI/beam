package beam.physsim.conditions

import org.matsim.api.core.v01.network.Link

import java.lang.Math._

object DoubleParking {

  /**
    * Introduced this trait in order to have more clear way of using this function. Also we can document the method.
    * But one can use an anonymus function to emulate this trait.
    */
  trait CapacityReductionFunction {

    /**
      * This function needs calculate the new link capacity if some vehicles are double parked on the link.
      * If there's no double parked vehicles (numberOfDoubleParking == 0) then capacity is considered unchanged and
      * this function is not called.
      * @param time time of the day
      * @param link the link
      * @param numberOfDoubleParked number of double parked vehicles
      * @param capacity current link capacity that is calculated basing on other factors
      * @return the new capacity of this link (how many vehicles can get throug it per hour)
      */
    def calculateCapacity(time: Double, link: Link, numberOfDoubleParked: Int, capacity: Double): Double
  }

  class SimpleCapacityReductionFunction(laneWeight: Double, capacityFraction: Double)
      extends CapacityReductionFunction {

    /**
      * This function needs calculate the new link capacity if some vehicles are double parked on the link.
      * If there's no double parked vehicles (numberOfDoubleParking == 0) then capacity is considered unchanged and
      * this function is not called.
      *
      * @param time                 time of the day
      * @param link                 the link
      * @param numberOfDoubleParked number of double parked vehicles
      * @param capacity             current link capacity that is calculated basing on other factors
      * @return the new capacity of this link (how many vehicles can get throug it per hour)
      */
    override def calculateCapacity(time: Double, link: Link, numberOfDoubleParked: Int, capacity: Double): Double = {
      val lanes = link.getNumberOfLanes(time)
      val newCapacity = if (lanes > 1) {
        val singleLaneCapacity = capacity / lanes
        //how many vehicles can be parked on the lane (average car takes 5 meteres of a lane)
        val totalVehicleLaneParkingCapacity = max(link.getLength / 5.0, 1.0)
        (lanes - laneWeight * numberOfDoubleParked / totalVehicleLaneParkingCapacity) * singleLaneCapacity
      } else {
        val coef = pow(capacityFraction, numberOfDoubleParked)
        capacity * coef
      }
      // capacity can not be zero (or a division by zero can happen)
      // we also should avoid negative capacity since it has no sense.
      // so we choose an arbitrary small number
      max(newCapacity, 0.00001)
    }
  }

}
