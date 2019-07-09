package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{RunConfig, TransformForPopulation}

object FollowPerson extends App {

  val sflight = RunConfig.trackPerson(
    "D:/Work/BEAM/Via-fs-light/2.events.xml",
    "D:/Work/BEAM/Via-fs-light/physsim-network.xml",
    "033000-2014000885580-0-2095585",
    "track_"
  )

  val sfbay = RunConfig.trackPerson(
    "D:/Work/BEAM/sfbay-smart-base/30.events.csv",
    "D:/Work/BEAM/sfbay-smart-base/physSimNetwork.xml",
    "2737248",
    ""
  )

  TransformForPopulation.transformAndWrite(sflight)
}
