package beam.router.skim.readonly

import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.SkimsUtils.timeToBin
import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.router.skim.core.ODVehicleTypeSkimmer.{ODVehicleTypeSkimmerInternal, ODVehicleTypeSkimmerKey}
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class ODVehicleTypeSkims extends AbstractSkimmerReadOnly {

  def getSkimValue(
    time: Int,
    vehicleCategory: VehicleCategory,
    primaryFuelType: FuelType,
    secondaryFuelType: FuelType,
    orig: Id[TAZ],
    dest: Id[TAZ]
  ): Option[ODVehicleTypeSkimmerInternal] = {
    val key: ODVehicleTypeSkimmerKey =
      ODVehicleTypeSkimmerKey(timeToBin(time), vehicleCategory, primaryFuelType, secondaryFuelType, orig, dest)

    getSkimValueByKey(key)
  }
}
