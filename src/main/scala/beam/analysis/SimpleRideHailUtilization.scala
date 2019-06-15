package beam.analysis

import java.util

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._

class SimpleRideHailUtilization extends IterationSummaryAnalysis {
  // Offset is number of passengers, value is number of rides with that amount of passengers
  private var overallRideStat: Array[Int] = Array.fill[Int](0)(0)

  override def processStats(event: Event): Unit = {
    event match {
      case pte: PathTraversalEvent if pte.vehicleId.toString.contains("rideHailVehicle-") =>
        handle(pte.numberOfPassengers)
      case _ =>
    }
  }

  override def resetStats(): Unit = {
    overallRideStat = Array.fill[Int](0)(0)
  }

  override def getSummaryStats: util.Map[String, java.lang.Double] = {
    val summaryMap = overallRideStat.zipWithIndex
      .map {
        case (rides, numOfPassenger) =>
          val value: java.lang.Double = java.lang.Double.valueOf(rides.toString)
          s"RideTripsWith${numOfPassenger}Passengers" -> value
      }
      .toMap
      .asJava
    summaryMap
  }

  def getStat(numOfPassengers: Int): Option[Int] = {
    overallRideStat.lift(numOfPassengers)
  }

  def handle(numOfPassengers: Int): Int = {
    val arrToUpdate = if (numOfPassengers >= overallRideStat.length) {
      val newArr = Array.fill[Int](numOfPassengers + 1)(0)
      Array.copy(overallRideStat, 0, newArr, 0, overallRideStat.length)
      overallRideStat = newArr
      newArr
    } else {
      overallRideStat
    }
    incrementCounter(arrToUpdate, numOfPassengers)
  }

  private def incrementCounter(arrToUpdate: Array[Int], offset: Int): Int = {
    val updatedCounter = arrToUpdate(offset) + 1
    arrToUpdate.update(offset, updatedCounter)
    updatedCounter
  }
}
