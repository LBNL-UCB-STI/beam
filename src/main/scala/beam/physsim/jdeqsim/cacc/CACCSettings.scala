package beam.physsim.jdeqsim.cacc

import beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions.RoadCapacityAdjustmentFunction

case class CACCSettings(
  isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  roadCapacityAdjustmentFunction: RoadCapacityAdjustmentFunction
)
