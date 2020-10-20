package beam.router.graphhopper

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelTypePrices
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.graphhopper.CarGraphHopperWrapper.{BEAM_PROFILE, BEAM_PROFILE_NAME, FASTEST_PROFILE, FASTEST_PROFILE_NAME}
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
      BEAM_PROFILE
    } else {
      FASTEST_PROFILE
    }
  }

  override protected def createGraphHopper(): GraphHopper = {
    new BeamGraphHopper(wayId2TravelTime)
  }

  override protected def prepareRequest(request: GHRequest): Unit = {
    if (carRouter == "quasiDynamicGH") {
      request.setProfile(BEAM_PROFILE_NAME)
    } else {
      request.setProfile(FASTEST_PROFILE_NAME)
    }

    request.setPathDetails(
      Seq(
        Parameters.Details.EDGE_ID,
        BeamTimeDetails.BEAM_TIME,
        BeamTimeReverseDetails.BEAM_REVERSE_TIME
      ).asJava
    )
  }

  override protected def getLinkTravelTimes(
                                             responsePath: ResponsePath,
                                             totalTravelTime: Int): IndexedSeq[Double] = {
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
    DrivingCost.estimateDrivingCost(beamLeg, vehicleTypes(vehicleTypeId), fuelTypePrices)
  }
}

object CarGraphHopperWrapper {
  val BEAM_PROFILE_NAME = "beam_car"
  val FASTEST_PROFILE_NAME = "fastest_car"

  val BEAM_PROFILE = getBeamProfile()
  val FASTEST_PROFILE = getFastestProfile()

  private def getBeamProfile() = {
    val profile = new Profile(BEAM_PROFILE_NAME)
    profile.setVehicle("car")
    profile.setWeighting(BeamWeighting.NAME)
    profile.setTurnCosts(false)
    profile
  }

  private def getFastestProfile() = {
    val fastestCarProfile = new Profile(FASTEST_PROFILE_NAME)
    fastestCarProfile.setVehicle("car")
    fastestCarProfile.setWeighting("fastest")
    fastestCarProfile.setTurnCosts(false)
    fastestCarProfile
  }
}
