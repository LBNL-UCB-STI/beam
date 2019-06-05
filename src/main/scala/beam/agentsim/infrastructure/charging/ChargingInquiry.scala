package beam.agentsim.infrastructure.charging
import scala.collection.concurrent.TrieMap

import beam.agentsim.agents.PersonAgent.BasePersonData
import beam.agentsim.agents.choice.logit.{MultinomialLogit, UtilityFunctionOperation}
import beam.agentsim.agents.planning.Tour
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.FuelType.{Electricity, Gasoline}
import beam.agentsim.infrastructure.parking.ParkingZoneSearch
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.vehicles.VehicleType

case class ChargingInquiry(
  utility: Option[MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String]],
  plugData: Option[List[ChargingPointType]],
  vehicle: BeamVehicle,
  vot: Double
)

object ChargingInquiry {

  def apply(
    utility: Option[MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String]],
    plugData: Option[List[ChargingPointType]],
    vehicle: BeamVehicle,
    vot: Double
  ): Some[ChargingInquiry] = {
    Some(new ChargingInquiry(utility, plugData, vehicle, vot))
  }

  def getChargingInquiryData(
    beamSkimmer: BeamSkimmer,
    personData: BasePersonData,
    currentTour: Tour,
    nextActivity: Option[Activity],
    privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle],
    beamVehicle: BeamVehicle,
    valueOfTime: Double,
  ): Option[ChargingInquiry] = {

    // todo JH review logic
    // the utility function // as todo config param
    // todo calibrate
    val beta1 = 1
    val beta2 = 1
    val beta3 = 0.001
    val distanceBuffer = 25000 // in meter (the distance that should be considered as buffer for range estimation

    // todo for all charginginquiries: extract plugs from vehicles and pass it over to ZM

    val commonUtilityParams: Map[String, UtilityFunctionOperation] = Map(
      "energyPriceFactor" -> UtilityFunctionOperation("multiplier", -beta1),
      "distanceFactor"    -> UtilityFunctionOperation("multiplier", -beta2),
      "installedCapacity" -> UtilityFunctionOperation("multiplier", -beta3)
    )

    val mnl = new MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String](Map.empty, commonUtilityParams)

    (beamVehicle.beamVehicleType.primaryFuelType, beamVehicle.beamVehicleType.secondaryFuelType) match {
      case (Electricity, None) => { //BEV
        //calculate the remaining driving distance in meters, reduced by 10% of the installed battery capacity as safety margin
        val remainingDrivingDist = (privateVehicles(personData.currentVehicle.head)
          .primaryFuelLevelInJoules / beamVehicle.beamVehicleType.primaryFuelConsumptionInJoulePerMeter) - distanceBuffer
//        log.debug(s"Remaining distance until BEV has only 10% of it's SOC left =  $remainingDrivingDist meter")

        val remainingTourDist = nextActivity match {
          case Some(nextAct) =>
            val nextActIdx = currentTour.tripIndexOfElement(nextAct)
            currentTour.trips
              .slice(nextActIdx, currentTour.trips.length)
              .sliding(2, 1)
              .toList // todo try without list
              .foldLeft(0d) { (sum, pair) =>
              sum + Math
                .ceil(
                  beamSkimmer
                    .getTimeDistanceAndCost(
                      pair.head.activity.getCoord,
                      pair.last.activity.getCoord,
                      0,
                      CAR,
                      beamVehicle.beamVehicleType.id
                    )
                    .distance
                )
            }
          case None =>
            0 // if we don't have any more trips we don't need a chargingInquiry as we are @home again => assumption: charging @home always takes place
        }

        remainingTourDist match {
          case 0 => None
          case _ if remainingDrivingDist <= remainingTourDist =>
            ChargingInquiry(None, None, beamVehicle, valueOfTime) // must
          case _ if remainingDrivingDist > remainingTourDist =>
            ChargingInquiry(Some(mnl), None, beamVehicle, valueOfTime) // opportunistic
        }

      }
      case (Electricity, Some(Gasoline)) => { // PHEV
//        log.debug("ChargingInquiry by PHEV {}.", beamVehicle.id)
        ChargingInquiry(Some(mnl), None, beamVehicle, valueOfTime) // PHEV is always opportunistic
      }
      case _ => None
    }
  }

//  def getChargingInquiryData(
//    personData: BasePersonData,
//    beamVehicle: BeamVehicle
//  ): Option[ChargingInquiry] = {
//
//    // todo JH review logic
//    // the utility function // as todo config param
//    // todo calibrate
//    val beta1 = 1
//    val beta2 = 1
//    val beta3 = 0.001
//    val distanceBuffer = 25000 // in meter (the distance that should be considered as buffer for range estimation
//
//    // todo for all charginginquiries: extract plugs from vehicles and pass it over to ZM
//    val mnl = MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String](
//      Map.empty,
//      Map(
//        "energyPriceFactor" -> UtilityFunctionOperation.Multiplier(-1.0),
//        "distanceFactor" -> UtilityFunctionOperation.Multiplier(-1.0),
//        "installedCapacity" -> UtilityFunctionOperation.Multiplier(-0.001)
//      )
//    )
//
//    (beamVehicle.beamVehicleType.primaryFuelType, beamVehicle.beamVehicleType.secondaryFuelType) match {
//      case (Electricity, None) => { //BEV
//        //calculate the remaining driving distance in meters, reduced by 10% of the installed battery capacity as safety margin
//        val remainingDrivingDist = (beamServices
//          .privateVehicles(personData.currentVehicle.head)
//          .primaryFuelLevelInJoules / beamVehicle.beamVehicleType.primaryFuelConsumptionInJoulePerMeter) - distanceBuffer
//        log.debug(s"Remaining distance until BEV has only 10% of it's SOC left =  $remainingDrivingDist meter")
//
//        val remainingTourDist = nextActivity(personData) match {
//          case Some(nextAct) =>
//            val nextActIdx = currentTour(personData).tripIndexOfElement(nextAct)
//            currentTour(personData).trips
//              .slice(nextActIdx, currentTour(personData).trips.length)
//              .sliding(2, 1)
//              .toList // todo try without list
//              .foldLeft(0d) { (sum, pair) =>
//              sum + Math
//                .ceil(
//                  beamSkimmer
//                    .getTimeDistanceAndCost(
//                      pair.head.activity.getCoord,
//                      pair.last.activity.getCoord,
//                      0,
//                      CAR,
//                      beamVehicle.beamVehicleType.id
//                    )
//                    .distance
//                )
//            }
//          case None =>
//            0 // if we don't have any more trips we don't need a chargingInquiry as we are @home again => assumption: charging @home always takes place
//        }
//
//        remainingTourDist match {
//          case 0 => None
//          case _ if remainingDrivingDist <= remainingTourDist =>
//            ChargingInquiry(None, None, beamVehicle, attributes.valueOfTime) // must
//          case _ if remainingDrivingDist > remainingTourDist =>
//            ChargingInquiry(Some(mnl), None, beamVehicle, attributes.valueOfTime) // opportunistic
//        }
//
//      }
//      case (Electricity, Some(Gasoline)) => { // PHEV
//        log.debug("ChargingInquiry by PHEV {}.", beamVehicle.id)
//        ChargingInquiry(Some(mnl), None, beamVehicle, attributes.valueOfTime) // PHEV is always opportunistic
//      }
//      case _ => None
//    }
//  }

}
