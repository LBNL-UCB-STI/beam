package beam.router.skim

import beam.sim.BeamScenario
import com.google.inject.{Inject, Injector}
import org.matsim.core.controler.MatsimServices

class ODSkimmer @Inject()(val injector: Injector)
  extends AbstractBeamSkimmer(
    injector.getInstance(classOf[BeamScenario]),
    injector.getInstance(classOf[MatsimServices])
  ) {
  import ODSkimmer._

  override protected def cvsFileName: String = "ODSkim"

  override protected def cvsFileHeader: String = "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"

  override protected def keyDataToStrMap(keyVal: (BeamSkimmerKey, BeamSkimmerData)): Map[String, String] = ???

  override protected def strMapToKeyData(strMap: Map[String, String]): (BeamSkimmerKey, BeamSkimmerData) = ???

  override protected def mergeDataWithSameKey(storedData: BeamSkimmerData, newData: BeamSkimmerData): BeamSkimmerData = ???

  override protected def dataToPersistAtEndOfIteration(persistedData: Map[BeamSkimmerKey, BeamSkimmerData], collectedData: Map[BeamSkimmerKey, BeamSkimmerData]): Map[BeamSkimmerKey, BeamSkimmerData] = ???

  override def checkIfDataShouldBePersistedThisIteration(iteration: Int) = {
    iteration % beamScenario.beamConfig.beam.beamskimmer.writeObservedSkimsInterval == 0
  }
}

object ODSkimmer {
  import AbstractBeamSkimmer._


}