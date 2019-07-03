package beam.router.skim

import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.sim.vehiclesharing.VehicleManager
import beam.sim._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.MatsimServices

import scala.collection.{immutable, mutable}

case class FlatSkimmerKey(
  timBin: Int,
  idTaz: Id[TAZ],
  hexIndex: String,
  idVehManager: Id[VehicleManager],
  label: String
) extends BeamObserverKey

case class FlatSkimmerData(value: Double) extends BeamObserverData

class FlatSkimmer(beamScenario: BeamScenario, matsimServices: MatsimServices) extends BeamObserver(beamScenario) {
  FlatSkimmer.h3taz = Some(H3TAZ.build(matsimServices.getScenario, beamScenario.tazTreeMap))
  override def cvsFileName: String = "flatSkim.csv.gz"
  override def cvsFileHeader: String = "timeBin,idTaz,hexIndex,idVehManager,label,value"
  override def strMapToKeyData(strMap: immutable.Map[String, String]): (BeamObserverKey, BeamObserverData) = {
    val time = strMap("timeBin").toInt
    val tazId = Id.create(strMap("idTaz"), classOf[TAZ])
    val hex = strMap("hexIndex")
    val manager = Id.create(strMap("idVehManager"), classOf[VehicleManager])
    val label = strMap("label")
    val value = strMap("value").toDouble
    (FlatSkimmerKey(time, tazId, hex, manager, label), FlatSkimmerData(value))
  }
  override def keyDataToStrMap(keyVal: (BeamObserverKey, BeamObserverData)): immutable.Map[String, String] = {
    val imap = mutable.Map.empty[String, String]
    imap.put("timeBin", keyVal._1.asInstanceOf[FlatSkimmerKey].timBin.toString)
    imap.put("idTaz", keyVal._1.asInstanceOf[FlatSkimmerKey].idTaz.toString)
    imap.put("hexIndex", keyVal._1.asInstanceOf[FlatSkimmerKey].hexIndex.toString)
    imap.put("idVehManager", keyVal._1.asInstanceOf[FlatSkimmerKey].idVehManager.toString)
    imap.put("label", keyVal._1.asInstanceOf[FlatSkimmerKey].label.toString)
    imap.put("value", keyVal._2.asInstanceOf[FlatSkimmerData].value.toString)
    imap.toMap
  }
  override def mergeDataWithSameKey(storedData: BeamObserverData, newData: BeamObserverData): BeamObserverData = {
    FlatSkimmerData(storedData.asInstanceOf[FlatSkimmerData].value + newData.asInstanceOf[FlatSkimmerData].value)
  }
  override def dataToPersistAtEndOfIteration(
    persistedData: immutable.Map[BeamObserverKey, BeamObserverData],
    collectedData: immutable.Map[BeamObserverKey, BeamObserverData]
  ): immutable.Map[BeamObserverKey, BeamObserverData] = collectedData
  override def checkIfDataShouldBePersistedThisIteration(iteration: Int) = {
    iteration % beamScenario.beamConfig.beam.outputs.writeSkimsInterval == 0
  }
}

object FlatSkimmer {
  import BeamObserver._
  var h3taz: Option[H3TAZ] = None

  def getEvent(time: Double, bin: Int, coord: Coord, vehMng: Id[VehicleManager], label: String, value: Double) =
    new BeamObserverEvent(time) {
      override def getEventType: String = "FlatSkimmerEvent"
      override def getKey: BeamObserverKey = {
        var hexIndex = "NA"
        var idTaz = H3TAZ.emptyTAZId
        FlatSkimmer.h3taz match {
          case Some(h3taz) =>
            h3taz.getHex(coord.getX, coord.getY) match {
              case Some(hex) =>
                hexIndex = hex
                idTaz = h3taz.getTAZ(hex)
              case _ =>
            }
          case _ =>
        }
        FlatSkimmerKey(bin, idTaz, hexIndex, vehMng, label)
      }
      override def getData: BeamObserverData = FlatSkimmerData(value)
    }
}
