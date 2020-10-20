package beam.router.graphhopper

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelTypePrices
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.graphhopper.WalkGraphHopperWrapper.{FASTEST_PROFILE, FASTEST_PROFILE_NAME}
import beam.router.model.BeamLeg
import beam.sim.common.GeoUtils
import com.graphhopper.{GHRequest, GraphHopper, ResponsePath}
import com.graphhopper.config.Profile
import com.graphhopper.util.Parameters
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._

class WalkGraphHopperWrapper(
  graphDir: String,
  geo: GeoUtils,
  id2Link: Map[Int, (Coord, Coord)]
) extends GraphHopperWrapper(graphDir, geo, id2Link) {

  override protected val beamMode: Modes.BeamMode = BeamMode.CAR

  override def getProfile(): Profile = FASTEST_PROFILE

  override def createGraphHopper(): GraphHopper = {
    new GraphHopper()
  }

  override def prepareRequest(request: GHRequest): Unit = {
    request.setProfile(FASTEST_PROFILE_NAME)
    request.setPathDetails(Seq(Parameters.Details.EDGE_ID, Parameters.Details.TIME).asJava)
  }

  override protected def getLinkTravelTimes(responsePath: ResponsePath, totalTravelTime: Int): IndexedSeq[Double] = {
    responsePath.getPathDetails
      .asScala(Parameters.Details.TIME)
      .asScala
      .map(pd => pd.getValue.asInstanceOf[Long].toDouble / 1000.0)
      .toIndexedSeq
  }

  override protected def getCost(beamLeg: BeamLeg, vehicleTypeId: Id[BeamVehicleType]): Double = 0.0
}

object WalkGraphHopperWrapper {
  val FASTEST_PROFILE_NAME = "fastest_foot"
  val FASTEST_PROFILE = getFastestProfile()

  private def getFastestProfile() = {
    val fastestFootProfile = new Profile(FASTEST_PROFILE_NAME)
    fastestFootProfile.setVehicle("foot")
    fastestFootProfile.setWeighting("fastest")
    fastestFootProfile.setTurnCosts(false)

    fastestFootProfile
  }
}
