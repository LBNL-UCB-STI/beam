package helics

import com.java.helics._
import com.java.helics.helicsJNI._
import org.matsim.api.core.v01.Coord

import scala.collection.mutable.ListBuffer

case class BeamFederate(name: String, bin: Int) {
  System.loadLibrary("JNIhelics")

  private val fedinfo = helics.helicsCreateFederateInfo
  helics.helicsFederateInfoSetCoreName(fedinfo, name)
  helics.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")
  helics.helicsFederateInfoSetCoreInitString(fedinfo, "--federates=1")
  helics.helicsFederateInfoSetTimeProperty(fedinfo, helics_property_time_delta_get(), bin)
  helics.helicsFederateInfoSetIntegerProperty(fedinfo, helics_property_int_log_level_get(), 1)

  private val cfed = helics.helicsCreateCombinationFederate(name, fedinfo)
  private val plugInVehId =
    helics.helicsFederateRegisterPublication(cfed, "plugInVehId", helics_data_type.helics_data_type_string, "")
  private val plugInSOC =
    helics.helicsFederateRegisterPublication(cfed, "plugInSOC", helics_data_type.helics_data_type_double, "")
  private val plugInLng =
    helics.helicsFederateRegisterPublication(cfed, "plugInLng", helics_data_type.helics_data_type_double, "")
  private val plugInLat =
    helics.helicsFederateRegisterPublication(cfed, "plugInLat", helics_data_type.helics_data_type_double, "")
  private val plugOutVehId =
    helics.helicsFederateRegisterPublication(cfed, "plugOutVehId", helics_data_type.helics_data_type_string, "")
  private val plugOutSOC =
    helics.helicsFederateRegisterPublication(cfed, "plugOutSOC", helics_data_type.helics_data_type_double, "")
  private val plugOutLng =
    helics.helicsFederateRegisterPublication(cfed, "plugOutLng", helics_data_type.helics_data_type_double, "")
  private val plugOutLat =
    helics.helicsFederateRegisterPublication(cfed, "plugOutLat", helics_data_type.helics_data_type_double, "")

  helics.helicsFederateEnterInitializingMode(cfed)
  helics.helicsFederateEnterExecutingMode(cfed)

  def publishPlugInEvent(time: Int, vehId: String, location: Coord, soc: Double): Unit = {
    helics.helicsPublicationPublishString(plugInVehId, vehId)
    helics.helicsPublicationPublishDouble(plugInSOC, soc)
    helics.helicsPublicationPublishDouble(plugInLng, location.getX)
    helics.helicsPublicationPublishDouble(plugInLat, location.getY)
  }

  def publishPlugOutEvent(time: Int, vehId: String, location: Coord, soc: Double): Unit = {
    helics.helicsPublicationPublishString(plugOutVehId, vehId)
    helics.helicsPublicationPublishDouble(plugOutSOC, soc)
    helics.helicsPublicationPublishDouble(plugOutLng, location.getX)
    helics.helicsPublicationPublishDouble(plugOutLat, location.getY)
  }

  def close(): Unit = {
    helics.helicsFederateFinalize(cfed)
    helics.helicsFederateFree(cfed)
    helics.helicsCloseLibrary()
  }
}

object BeamFederate {
  var outGoing: Option[BeamFederate] = None

  def getBeamFederate1(bin: Int): BeamFederate = {
    if (outGoing.isEmpty)
      outGoing = Some(BeamFederate("BeamFederate1", bin))
    outGoing.get
  }
}
