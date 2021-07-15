package beam.analysis

import beam.agentsim.events.ModeChoiceOccurredEvent
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.BeamServices
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}
import org.matsim.core.events.handler.BasicEventHandler

class ModeChoiceAlternativesCollector(beamServices: BeamServices)
    extends BasicEventHandler
    with IterationEndsListener
    with IterationStartsListener
    with LazyLogging {

  logger.info(s"Created ModeChoiceAlternativesCollector with hashcode: ${this.hashCode()}")

  private var iteration = 0
  private var csvWriter: CsvWriter = _
  private var csvFilePath: String = _

  def getTripCategory(alternatives: IndexedSeq[EmbodiedBeamTrip]): Int = {
    def legIsNotEmpty(leg: EmbodiedBeamLeg): Boolean =
      leg.beamLeg.travelPath.linkIds.nonEmpty || leg.beamLeg.travelPath.transitStops.nonEmpty

    val allAlternativesHasAtLeastOneNonEmptyLeg = alternatives
      .foldLeft(true)((acc, trip) => {
        val tripContainsOneNonEmptyLeg = trip.legs.exists(l => legIsNotEmpty(l))
        acc && tripContainsOneNonEmptyLeg
      })

    if (allAlternativesHasAtLeastOneNonEmptyLeg) alternatives.size else 0
  }

  override def handleEvent(event: Event): Unit = {
    event match {
      case mco: ModeChoiceOccurredEvent =>
        val tripCategory = getTripCategory(mco.alternatives)

        mco.alternatives.zipWithIndex
          .foreach {
            case (trip: EmbodiedBeamTrip, idx) =>
              val tripType = trip.tripClassifier.value.toLowerCase()
              (mco.modeCostTimeTransfers.get(tripType), mco.alternativesUtility.get(tripType)) match {
                case (Some(tripCostTimeTransfer), Some(tripUtility)) =>
                  val duration = trip.legs.map(_.beamLeg.duration).sum
                  trip.legs.foreach(leg =>
                    writeAlternative(
                      mco.personId,
                      idx,
                      idx == mco.chosenAlternativeIdx,
                      tripCostTimeTransfer,
                      tripUtility,
                      duration,
                      tripType,
                      leg,
                      tripCategory
                    )
                  )
                case _ =>
              }

            case _ =>
          }

      case _ =>
    }
  }

  def writeAlternative(
    personId: String,
    tripNumber: Int,
    wasChosen: Boolean,
    altCostTimeTransfer: ModeChoiceOccurredEvent.AltCostTimeTransfer,
    altUtility: ModeChoiceOccurredEvent.AltUtility,
    tripDuration: Int,
    tripType: String,
    leg: EmbodiedBeamLeg,
    tripCategory: Int
  ): Unit = {
    val beamLegType = if (leg.beamVehicleTypeId != null) leg.beamVehicleTypeId.toString else "DEFAULT"
    val vehicleType = if (leg.isRideHail) "RH_" + beamLegType else beamLegType

    val empty = ""
    val transit: String = leg.beamLeg.travelPath.transitStops match {
      case Some(tStops) =>
        s"agency:${tStops.agencyId}, route:${tStops.routeId}, vehicle:${tStops.vehicleId}, ${tStops.fromIdx}->${tStops.toIdx}"
      case _ => empty
    }

    val linkIds =
      if (leg.beamLeg.travelPath.linkIds.isEmpty) empty
      else leg.beamLeg.travelPath.linkIds.mkString(",")

    val linkTravelTime =
      if (leg.beamLeg.travelPath.linkTravelTime.isEmpty) empty
      else leg.beamLeg.travelPath.linkTravelTime.mkString(",")

    csvWriter.writeRow(
      IndexedSeq(
        personId,
        tripNumber,
        if (wasChosen) 1 else 0,
        tripType,
        altCostTimeTransfer.cost,
        altCostTimeTransfer.time,
        altCostTimeTransfer.numTransfers,
        altUtility.utility,
        altUtility.expUtility,
        tripDuration,
        leg.beamLeg.mode,
        vehicleType,
        leg.beamVehicleId,
        leg.beamLeg.startTime,
        "\"" + linkIds + "\"",
        "\"" + linkTravelTime + "\"",
        "\"" + transit + "\"",
        tripCategory
      )
    )
  }

  def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    csvWriter.close()
    logger.info("CsvWriter closed for file " + csvFilePath)
  }

  def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    iteration = event.getIteration

    csvFilePath = beamServices.matsimServices.getControlerIO.getIterationFilename(
      iteration,
      "modeChoiceAlternativesWhenRHPooled.csv.gz"
    )

    csvWriter = new CsvWriter(
      csvFilePath,
      Vector(
        "personId",
        "altNumber",
        "wasChosen",
        "altType",
        "altCost",
        "altTime",
        "altTransferCnt",
        "altUtility",
        "altExpUtility",
        "altDuration",
        "vehicleMode",
        "vehicleType",
        "vehicleId",
        "time",
        "links",
        "linksTravelTime",
        "transit",
        "tripCategory"
      )
    )

    logger.info(s"ScvWriter created for file " + csvFilePath)
  }
}
