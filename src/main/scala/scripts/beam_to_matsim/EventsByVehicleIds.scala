package scripts.beam_to_matsim

import scripts.beam_to_matsim.events_filter.MutableVehiclesFilter
import scripts.beam_to_matsim.io.Utils

/*
a script to generate Via events for specific vehicle ids
 */

object EventsByVehicleIds extends App {

  // format: off
  /********************************************************************************************************
    ./gradlew execute -PmainClass=scripts.beam_to_matsim.EventsByVehicleIds -PappArgs="[
      '<beam events csv file>',
      '<via events output xml file>',
      '<vehicleId1>,<vehicleId2>,<vehicleId3>,<vehicleId4>',
    ]" -PmaxRAM=16g
  *********************************************************************************************************/
  // format: on

  val eventsFile = args(0)
  val outputFile = args(1)
  val selectedVehicleIds = args(2).split(",").map(_.trim)

  Console.println(s"going to transform BEAM events from $eventsFile and write them into $outputFile")
  Console.println(s"vehicle ids: ${selectedVehicleIds.mkString("[", ", ", "]")}")

  val filter = MutableVehiclesFilter((vehicleMode: String, vehicleType: String, vehicleId: String) =>
    selectedVehicleIds.contains(vehicleId)
  )

  Utils.buildViaFile(eventsFile, outputFile, filter)
}
