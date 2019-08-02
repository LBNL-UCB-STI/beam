package beam.agentsim.agents.ridehail.surgepricing
import beam.agentsim.agents.ridehail.surgepricing.RideHailSurgePricingManager.SurgePriceBin
import beam.sim.BeamServices
import com.google.inject.Inject

class DummyRideHailSurgePricingManager @Inject()(val beamServices: BeamServices) extends RideHailSurgePricingManager {

  override def initializeSurgePricingLevel(surgePriceBin: SurgePriceBin): SurgePriceBin = {
    surgePriceBin.copy(currentIterationSurgePriceLevel = 1)
  }

  override def updateSurgePricingLevel(surgePriceBin: SurgePriceBin): SurgePriceBin = {
    surgePriceBin
  }
}
