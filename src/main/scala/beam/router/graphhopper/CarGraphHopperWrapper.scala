package beam.router.graphhopper

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelTypePrices
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.graphhopper.CarGraphHopperWrapper.{BeamProfile, BeamProfileName, FastestProfile, FastestProfileName}
import beam.router.model.BeamLeg
import beam.sim.common.GeoUtils
import com.graphhopper.{GHRequest, GraphHopper, ResponsePath}
import com.graphhopper.config.Profile
import com.graphhopper.util.Parameters
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._

class CarGraphHopperWrapper(
  carRouter: String,
  graphDir: String,
  geo: GeoUtils,
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  fuelTypePrices: FuelTypePrices,
  wayId2TravelTime: Map[Long, Double],
  id2Link: Map[Int, (Coord, Coord)]
) extends GraphHopperWrapper(graphDir, geo, id2Link) {

  override protected val beamMode: Modes.BeamMode = BeamMode.CAR

  override protected def getProfile(): Profile = {
    if (carRouter == "quasiDynamicGH") {
      BeamProfile
    } else {
      FastestProfile
    }
  }

  override protected def createGraphHopper(): GraphHopper = {
    new BeamGraphHopper(wayId2TravelTime)
  }

  override protected def prepareRequest(request: GHRequest): Unit = {
    if (carRouter == "quasiDynamicGH") {
      request.setProfile(BeamProfileName)
    } else {
      request.setProfile(FastestProfileName)
    }

    request.setPathDetails(
      Seq(
        Parameters.Details.EDGE_ID,
        BeamTimeDetails.BEAM_TIME,
        BeamTimeReverseDetails.BEAM_REVERSE_TIME
      ).asJava
    )
  }

  override protected def getLinkTravelTimes(responsePath: ResponsePath, totalTravelTime: Int): IndexedSeq[Double] = {
    val allLinkTravelBeamTimes = responsePath.getPathDetails
      .asScala(BeamTimeDetails.BEAM_TIME)
      .asScala
      .map(pd => pd.getValue.asInstanceOf[Double])
      .toIndexedSeq

    val allLinkTravelBeamTimesReverse = responsePath.getPathDetails
      .asScala(BeamTimeReverseDetails.BEAM_REVERSE_TIME)
      .asScala
      .map(pd => pd.getValue.asInstanceOf[Double])
      .toIndexedSeq

    if (Math.abs(totalTravelTime - allLinkTravelBeamTimes.sum.toInt) <= 2) {
      allLinkTravelBeamTimes
    } else {
      allLinkTravelBeamTimesReverse
    }
  }

  override protected def getCost(beamLeg: BeamLeg, vehicleTypeId: Id[BeamVehicleType]): Double = {
    val vehicleType = vehicleTypes(vehicleTypeId)
    DrivingCost.estimateDrivingCost(
      beamLeg.travelPath.distanceInM,
      beamLeg.duration,
      vehicleType,
      fuelTypePrices(vehicleType.primaryFuelType)
    )
  }
}

object CarGraphHopperWrapper {
  val BeamProfileName = "beam_car"
  val FastestProfileName = "fastest_car"

  val BeamProfile = getBeamProfile()
  val FastestProfile = getFastestProfile()

  private def getBeamProfile() = {
    val profile = new Profile(BeamProfileName)
    profile.setVehicle("car")
    profile.setWeighting(BeamWeighting.Name)
    profile.setTurnCosts(false)
    profile
  }

  private def getFastestProfile() = {
    val fastestCarProfile = new Profile(FastestProfileName)
    fastestCarProfile.setVehicle("car")
    fastestCarProfile.setWeighting("fastest")
    fastestCarProfile.setTurnCosts(false)
    fastestCarProfile
  }
}
