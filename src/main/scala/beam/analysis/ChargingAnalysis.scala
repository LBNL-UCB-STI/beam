package beam.analysis

import java.{lang, util}

import scala.collection.JavaConverters._

import beam.agentsim.events._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import scala.collection.mutable

class ChargingAnalysis extends IterationSummaryAnalysis {
  private val chargingCountFileBaseName = "chargingCountPerDriver"
  private val averagekWhFileBaseName = "averagekWhPerDriver"

  sealed trait GeneralizedVehicleType
  case object CAV_Ridehail extends GeneralizedVehicleType
  case object Human_Ridehail extends GeneralizedVehicleType

  type DriverId = Id[Person]
  case class DriverChargingStats(count: Int, averageKWh: Double)

  val humanChargingStatsPerDriver = mutable.Map.empty[DriverId, DriverChargingStats]
  val cavChargingStatsPerDriver = mutable.Map.empty[DriverId, DriverChargingStats]

  override def processStats(event: Event): Unit = {
    event match {
      case refuelSessionEvent: RefuelSessionEvent =>
        val driverId: DriverId = refuelSessionEvent.getPersonId
        val vehicleType = refuelSessionEvent.vehicleType
        val eventEnergyInkWh = refuelSessionEvent.energyInJoules / 3600000
        val generalizedVehicleTypeOption: Option[GeneralizedVehicleType] =
          if (
            refuelSessionEvent.getAttributes
              .get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_ID)
              .toLowerCase
              .contains("ridehail")
          ) {
            if (vehicleType.isCaccEnabled) Some(CAV_Ridehail) else Some(Human_Ridehail)
          } else None
        generalizedVehicleTypeOption
          .map {
            case CAV_Ridehail   => cavChargingStatsPerDriver
            case Human_Ridehail => humanChargingStatsPerDriver
          }
          .map { chargingStatsPerDriver =>
            {
              chargingStatsPerDriver.get(driverId) match {
                case Some(chargingStats) => {
                  val incrementedCount = chargingStats.count + 1
                  val newAveragekWh =
                    ((chargingStats.averageKWh * chargingStats.count) + eventEnergyInkWh) / incrementedCount
                  chargingStatsPerDriver.put(driverId, DriverChargingStats(incrementedCount, newAveragekWh))
                }
                case None => chargingStatsPerDriver.put(driverId, DriverChargingStats(1, eventEnergyInkWh))
              }
            }
          }
      case _ =>
    }
  }

  override def resetStats(): Unit = {
    humanChargingStatsPerDriver.clear
    cavChargingStatsPerDriver.clear
  }

  def getSummaryStats(): util.Map[String, lang.Double] = {
    (
      (cavChargingStatsPerDriver.size match {
        case 0 =>
          Map(
            (chargingCountFileBaseName + "_CAV", java.lang.Double.valueOf(0.0)),
            (averagekWhFileBaseName + "_CAV", java.lang.Double.valueOf(0.0))
          )
        case _ =>
          Map(
            (
              chargingCountFileBaseName + "_CAV",
              java.lang.Double.valueOf(
                cavChargingStatsPerDriver.map(_._2.count).sum / cavChargingStatsPerDriver.size.doubleValue()
              )
            ),
            (
              averagekWhFileBaseName + "_CAV",
              java.lang.Double.valueOf(
                cavChargingStatsPerDriver.map(_._2.averageKWh).sum / cavChargingStatsPerDriver.size.doubleValue()
              )
            )
          )
      }) ++ (humanChargingStatsPerDriver.size match {
        case 0 =>
          Map(
            (chargingCountFileBaseName + "_Human", java.lang.Double.valueOf(0.0)),
            (averagekWhFileBaseName + "_Human", java.lang.Double.valueOf(0.0))
          )
        case _ =>
          Map(
            (
              chargingCountFileBaseName + "_Human",
              java.lang.Double.valueOf(
                humanChargingStatsPerDriver.map(_._2.count).sum / humanChargingStatsPerDriver.size.doubleValue()
              )
            ),
            (
              averagekWhFileBaseName + "_Human",
              java.lang.Double.valueOf(
                humanChargingStatsPerDriver.map(_._2.averageKWh).sum / humanChargingStatsPerDriver.size.doubleValue()
              )
            )
          )
      })
    ).asJava
  }
}
