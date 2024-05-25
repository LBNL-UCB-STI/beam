package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsRateFilterStore.EmissionsRateFilter
import beam.sim.common.{DoubleTypedRange, Range}
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.CsvParser

import scala.concurrent.{Await, Future}

class VehicleEmissions {}

object VehicleEmissions {

  object EmissionsRateFilterStore {
    //speed->(gradePercent->(weight->(numberOfLanes->rate)))
    type EmissionsRateFilter = Map[DoubleTypedRange, Map[DoubleTypedRange, Map[DoubleTypedRange, Map[Range, Double]]]]
  }

  trait EmissionsRateFilterStore {

    def getPrimaryConsumptionRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[EmissionsRateFilterStore.EmissionsRateFilter]]

    def getSecondaryConsumptionRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[EmissionsRateFilterStore.EmissionsRateFilter]]
    def hasPrimaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Boolean
    def hasSecondaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Boolean
  }

  class ConsumptionRateFilterStoreImpl(
                                        csvRecordsForFilePathUsing: (CsvParser, String) => Iterable[Record],
                                        baseFilePaths: IndexedSeq[String],
                                        primaryEmissionRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])],
                                        secondaryEmissionRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])]
                                      ) extends EmissionsRateFilterStore {

    override def getPrimaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Option[Future[EmissionsRateFilter]] = ???

    override def getSecondaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Option[Future[EmissionsRateFilter]] = ???

    override def hasPrimaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Boolean = ???

    override def hasSecondaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Boolean = ???
  }

}
