package beam.router.skim.readonly

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.SkimsUtils.timeToBin
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.ODVehicleTypeSkimmer.{
  ODVehicleTypeSkimmerInternal,
  ODVehicleTypeSkimmerKey,
  VehicleTypePart
}
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class ODVehicleTypeSkims extends AbstractSkimmerReadOnly {

  def getSkimValue(
    time: Int,
    vehicleTypePart: VehicleTypePart,
    orig: Id[TAZ],
    dest: Id[TAZ]
  ): Option[ODVehicleTypeSkimmerInternal] = {
    val key: ODVehicleTypeSkimmerKey = ODVehicleTypeSkimmerKey(timeToBin(time), vehicleTypePart, orig, dest)

    getSkimValueByKey(key)
  }
}
