package beam.router.graphhopper

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.graphhopper.BikeGraphHopperWrapper.FastestProfileName
import beam.router.model.BeamLeg
import beam.sim.common.GeoUtils
import com.graphhopper.config.Profile
import com.graphhopper.util.Parameters
import com.graphhopper.{GHRequest, GraphHopper, ResponsePath}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._

class BikeGraphHopperWrapper(
  graphDir: String,
  geo: GeoUtils,
  id2Link: Map[Int, (Coord, Coord)]
) extends GraphHopperWrapper(graphDir, geo, id2Link) {

  override protected val beamMode: Modes.BeamMode = BeamMode.BIKE

  override def getProfile(): Profile = BikeGraphHopperWrapper.getFastestProfile

  override def createGraphHopper(): GraphHopper = {
    new GraphHopper()
  }

  override def prepareRequest(request: GHRequest): Unit = {
    request.setProfile(FastestProfileName)
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

object BikeGraphHopperWrapper {
  val FastestProfileName = "best_bike"

  def getFastestProfile: Profile = {
    val profile = new Profile(FastestProfileName)
    profile.setVehicle("bike")
    profile.setWeighting("fastest")
    profile.setTurnCosts(false)

    profile
  }
}
