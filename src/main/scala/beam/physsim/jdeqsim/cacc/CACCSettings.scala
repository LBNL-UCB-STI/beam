package beam.physsim.jdeqsim.cacc

import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction

case class CACCSettings(
  isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  roadCapacityAdjustmentFunction: RoadCapacityAdjustmentFunction
)
