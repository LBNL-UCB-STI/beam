package scripts.beam_to_matsim

import beam.utils.beam_to_matsim.events_filter.MutableVehiclesFilter
import beam.utils.beam_to_matsim.io.{Reader, Writer}
import beam.utils.beam_to_matsim.transit.generator.TransitViaEventsGenerator

/*
a script to generate Via events for transit vehicles
 */

object EventsByTransitTypes extends App {

  // format: off
  /********************************************************************************************************
    ./gradlew execute -PmainClass=beam.utils.beam_to_matsim.scripts.via.EventsByTransitTypes -PappArgs="[
      '<beam events csv file>',
      '<via events output xml file>',
      '--ridehail', '<idleThresholdInSec>', '<rangeSize>', '<vehicleType1>,<vehicleType1>',
      '--common', '<idleThresholdInSec>', '<rangeSize>', '<vehicleType1>,<vehicleType1>'
    ]" -PmaxRAM=16g

    ./gradlew execute -PmainClass=beam.utils.beam_to_matsim.scripts.via.EventsByTransitTypes -PappArgs="[
      '<beam events csv file>',
      '<via events output xml file>',
      '--network', '<networkPath>', '<idleThresholdInSec>', '<rangeSize>', '<vehicleType1>,<vehicleType1>'
    ]" -PmaxRAM=16g
    *********************************************************************************************************/
  // format: on

  val eventsFile = args(0)
  val outputFile = args(1)
  val otherArgs = args.drop(2)

  val askedForPublicTransitEvents = otherArgs.headOption.contains("--network")

  val vehicleTypeToGenerator = {
    if (askedForPublicTransitEvents) processArgForWithoutLinks(otherArgs)
    else processArgForLinks(otherArgs)
  }

  val filter = MutableVehiclesFilter((vehicleMode: String, vehicleType: String, vehicleId: String) =>
    vehicleTypeToGenerator.keys.toList.contains(vehicleType)
  )

  val (vehiclesEvents, _) = Reader.readWithFilter(eventsFile, filter)

  val viaEvents = vehiclesEvents.flatMap(_.trip.toList).groupBy(_.vehicleType).flatMap { case (vehicleType, events) =>
    val viaEventsGenerator = vehicleTypeToGenerator(vehicleType)
    events
      .groupBy(_.vehicleId)
      .mapValues(es => viaEventsGenerator(es.toVector))
      .values
      .flatten
  }

  Writer.writeViaEvents(viaEvents, outputFile)

  private def processArgForLinks(args: Array[String]): Map[String, TransitViaEventsGenerator.EventsGenerator] = {
    args
      .grouped(4)
      .flatMap {
        case Array("--ridehail", idleThreshold, rangeSize, vehicleTypes) =>
          val generator = TransitViaEventsGenerator.ridehailViaEventsGenerator(idleThreshold.toInt, rangeSize.toInt)
          vehicleTypes.split(",").map(_.trim).map(_ -> generator)
        case Array("--common", idleThreshold, rangeSize, vehicleTypes) =>
          val generator =
            TransitViaEventsGenerator.commonViaEventsGeneratorWithLinks(idleThreshold.toInt, rangeSize.toInt)
          vehicleTypes.split(",").map(_.trim).map(_ -> generator)
      }
      .toMap
  }

  private def processArgForWithoutLinks(args: Array[String]): Map[String, TransitViaEventsGenerator.EventsGenerator] = {
    args
      .grouped(5)
      .flatMap { case Array("--network", networkPath, idleThreshold, rangeSize, vehicleTypes) =>
        val generator =
          TransitViaEventsGenerator.commonViaEventsGeneratorWithoutLinks(
            idleThreshold.toInt,
            rangeSize.toInt,
            networkPath
          )
        vehicleTypes.split(",").map(_.trim).map(_ -> generator)
      }
      .toMap
  }
}
