package beam.router.skim

import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.sim.BeamScenario
import beam.sim.vehiclesharing.VehicleManager
import com.google.inject.{Inject, Injector}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.MatsimServices

import scala.collection.{immutable, mutable}

class FlatSkimmer @Inject()(val injector: Injector)
    extends AbstractBeamSkimmer(
      injector.getInstance(classOf[BeamScenario]),
      injector.getInstance(classOf[MatsimServices])
    ) {
  import FlatSkimmer._
  val h3taz: H3TAZ = H3TAZ.build(matsimServices.getScenario, beamScenario.tazTreeMap)
  override def skimmerId: String = "flatSkim"
  override def cvsFileHeader: String = "timeBin,idTaz,hexIndex,idVehManager,label,value"
  override def strMapToKeyData(strMap: immutable.Map[String, String]): (BeamSkimmerKey, BeamSkimmerData) = {
    val time = strMap("timeBin").toInt
    val tazId = Id.create(strMap("idTaz"), classOf[TAZ])
    val hex = strMap("hexIndex")
    val manager = Id.create(strMap("idVehManager"), classOf[VehicleManager])
    val label = strMap("label")
    val value = strMap("value").toDouble
    (FlatSkimmerKey(time, tazId, hex, manager, label), FlatSkimmerData(value))
  }
  override def keyDataToStrMap(keyVal: (BeamSkimmerKey, BeamSkimmerData)): immutable.Map[String, String] = {
    val imap = mutable.Map.empty[String, String]
    imap.put("timeBin", keyVal._1.asInstanceOf[FlatSkimmerKey].timBin.toString)
    imap.put("idTaz", keyVal._1.asInstanceOf[FlatSkimmerKey].idTaz.toString)
    imap.put("hexIndex", keyVal._1.asInstanceOf[FlatSkimmerKey].hexIndex.toString)
    imap.put("idVehManager", keyVal._1.asInstanceOf[FlatSkimmerKey].idVehManager.toString)
    imap.put("label", keyVal._1.asInstanceOf[FlatSkimmerKey].label.toString)
    imap.put("value", keyVal._2.asInstanceOf[FlatSkimmerData].value.toString)
    imap.toMap
  }
  override def mergeDataWithSameKey(storedData: BeamSkimmerData, newData: BeamSkimmerData): BeamSkimmerData = {
    FlatSkimmerData(storedData.asInstanceOf[FlatSkimmerData].value + newData.asInstanceOf[FlatSkimmerData].value)
  }
  override def dataToPersistAtEndOfIteration(
    persistedData: immutable.Map[BeamSkimmerKey, BeamSkimmerData],
    collectedData: immutable.Map[BeamSkimmerKey, BeamSkimmerData]
  ): immutable.Map[BeamSkimmerKey, BeamSkimmerData] = collectedData
  override def checkIfDataShouldBePersistedThisIteration(iteration: Int) = {
    iteration % beamScenario.beamConfig.beam.outputs.writeSkimsInterval == 0
  }

}

object FlatSkimmer {
  import AbstractBeamSkimmer._
  case class FlatSkimmerKey(
    timBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehManager: Id[VehicleManager],
    label: String
  ) extends BeamSkimmerKey

  case class FlatSkimmerData(value: Double) extends BeamSkimmerData

  def getEvent(time: Double, bin: Int, coord: Coord, vehMng: Id[VehicleManager], label: String, value: Double) =
    new BeamSkimmerEvent(time) {
      override def getEventType: String = "FlatSkimmerEvent"
      override def getKey: BeamSkimmerKey = {
        var hexIndex = "NA"
        var idTaz = H3TAZ.emptyTAZId
        get("flatSkim") match {
          case Some(flatSkimmer: FlatSkimmer) =>
            flatSkimmer.h3taz.getHex(coord.getX, coord.getY) match {
              case Some(hex) =>
                hexIndex = hex
                idTaz = flatSkimmer.h3taz.getTAZ(hex)
              case _ =>
            }
          case _ =>
        }
        FlatSkimmerKey(bin, idTaz, hexIndex, vehMng, label)
      }
      override def getData: BeamSkimmerData = FlatSkimmerData(value)
    }

}
