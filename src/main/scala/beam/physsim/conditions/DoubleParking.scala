package beam.physsim.conditions

import org.matsim.api.core.v01.network.Link

import java.lang.Math._

object DoubleParking {

  /**
    * This trait defines the interface for modelling double parking capacity reduction.
    * But one can use an anonymous function to emulate this trait.
    */
  trait CapacityReductionFunction {

    /**
      * This function calculates the new link capacity if some vehicles are double parked on the link.
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

  class SimpleCapacityReductionFunction() extends CapacityReductionFunction {

    override def calculateCapacity(time: Double, link: Link, numberOfDoubleParked: Int, capacity: Double): Double = {
      val lanes = link.getNumberOfLanes(time)

      val singleLaneCapacity = capacity / lanes
      //how many vehicles can be parked on the lane (average car takes 5 meters of a lane)
      val totalVehicleLaneParkingCapacity = max(link.getLength / 5.0, 1.0)

      // if too much double parking, the physsim minimum speed parameter takes care of it
      val newCapacity = (lanes - numberOfDoubleParked / totalVehicleLaneParkingCapacity) * singleLaneCapacity

      // capacity can not be zero (or a division by zero can happen)
      // we also should avoid negative capacity since it does not make any sense.
      // so we choose an arbitrary small number
      max(newCapacity, 0.00001)
    }
  }

}
