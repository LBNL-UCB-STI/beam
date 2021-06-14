package beam.scoring

import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit
import beam.agentsim.events.{LeavingParkingEvent, ModeChoiceEvent, ReplanningEvent}
import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.replanning.ReplanningUtil
import beam.router.model.EmbodiedBeamTrip
import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import beam.sim.population.PopulationAdjustment._
import beam.sim.{
  BeamConfigChangesObservable,
  BeamConfigChangesObserver,
  BeamServices,
  MapStringDouble,
  OutputDataDescription
}
import beam.utils.{FileUtils, OutputDataDescriptor}
import com.typesafe.scalalogging.LazyLogging
import javax.inject.{Inject, Singleton}
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent}
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.scoring.{ScoringFunction, ScoringFunctionFactory}

import scala.collection.JavaConverters._
import scala.collection.mutable

@Singleton
class BeamScoringFunctionFactory @Inject()(
  beamServices: BeamServices,
  beamConfigChangesObservable: BeamConfigChangesObservable
) extends ScoringFunctionFactory
    with IterationEndsListener
    with BeamConfigChangesObserver
    with LazyLogging {

  beamConfigChangesObservable.addObserver(this)

  private var beamConfig = beamServices.beamConfig

  /**
    * A map that stores personId and his calculated trip scores (based on the corresponding beam scoring function).
    * The above trip score is calculated for all individuals in the scenario and is written to an output file at the end of the iteration.
    * */
  private val personTripScores = mutable.HashMap.empty[String, String]
  private val generalizedLinkStats = mutable.HashMap.empty[Int, String]
  private val linkAverageTravelTimes = mutable.HashMap.empty[Int, (Double, Int)]
  private val linkAverageCosts = mutable.HashMap.empty[Int, (Double, Int)]
  private val linkAverageGeneralizedCosts = mutable.HashMap.empty[Int, (Double, Int)]
  private val linkAverageGeneralizedTimes = mutable.HashMap.empty[Int, (Double, Int)]

  /**
    * Stores the person and his respective score in a in memory map till the end of the iteration
    * @param personId id of the person
    * @param score score calculated for the person
    * @return
    */
  def setPersonScore(personId: String, score: String): Option[String] = {
    personTripScores.put(personId, score)
  }

  def setGeneralizedLinkStats(linkId: Int, stats: String): Option[String] = {
    generalizedLinkStats.put(linkId, stats)
  }

  /**
    * Returns the stored person scores
    * @return
    */
  def getPersonScores: mutable.HashMap[String, String] = {
    personTripScores
  }

  def getAllGeneralizedLinkStats: mutable.HashMap[Int, String] = {
    generalizedLinkStats
  }

  override def createNewScoringFunction(person: Person): ScoringFunction = {
    new ScoringFunction {

      private var finalScore = 0.0
      private val trips = mutable.ListBuffer[EmbodiedBeamTrip]()
      private var leavingParkingEventScore = 0.0
      var rideHailDepart = 0

      override def handleEvent(event: Event): Unit = {
        event match {
          case modeChoiceEvent: ModeChoiceEvent =>
            trips.append(modeChoiceEvent.chosenTrip)
          case _: ReplanningEvent =>
            // FIXME? If this happens often, maybe we can optimize it:
            // trips is list buffer meaning removing is O(n)
            trips.remove(trips.size - 1)
          case leavingParkingEvent: LeavingParkingEvent =>
            leavingParkingEventScore += leavingParkingEvent.score
          case e: PersonArrivalEvent =>
            // Here we modify the last leg of the trip (the dummy walk leg) to have the right arrival time
            // This will therefore now accounts for dynamic delays or difference between quoted ride hail trip time and actual
            val bodyVehicleId = trips.head.legs.head.beamVehicleId
            val bodyVehicleTypeId = trips.head.legs.head.beamVehicleTypeId
            @SuppressWarnings(Array("UnsafeTraversableMethods"))
            val lastTrip = trips.last
            trips.update(
              trips.size - 1,
              PersonAgent.correctTripEndTime(lastTrip, e.getTime().toInt, bodyVehicleId, bodyVehicleTypeId)
            )
          case _ =>
        }
      }

      override def addMoney(amount: Double): Unit = {}

      override def agentStuck(time: Double): Unit = {}

      override def handleLeg(leg: Leg): Unit = {}

      override def finish(): Unit = {
        val attributes =
          person.getCustomAttributes.get(BEAM_ATTRIBUTES).asInstanceOf[AttributesOfIndividual]

        val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory(attributes)

        // The scores attribute is only relevant to LCCM, but we need to include a default value to avoid NPE during writing of plans
        person.getSelectedPlan.getAttributes
          .putAttribute("scores", MapStringDouble(Map("NA" -> Double.NaN)))

        val personLegs = person.getSelectedPlan.getPlanElements.asScala.collect { case leg: Leg => leg }
        if (personLegs.isEmpty) {
          val newPlan = ReplanningUtil.addBeamTripsToPlanWithOnlyActivities(person.getSelectedPlan, trips.toVector)
          person.addPlan(newPlan)
          person.removePlan(person.getSelectedPlan)
          person.setSelectedPlan(newPlan)
        }
        trips.zip(personLegs).map {
          case (trip, leg) =>
            leg.getAttributes.putAttribute("vehicles", trip.vehiclesInTrip.mkString(","))
        }

        val allDayScore = modeChoiceCalculator.computeAllDayUtility(trips, person, attributes)
        val personActivities = person.getSelectedPlan.getPlanElements.asScala
          .collect {
            case activity: Activity => activity
          }
          .filter(activity => !activity.getType.equalsIgnoreCase("Home") & !activity.getType.equalsIgnoreCase("Work"))
        val activityScore = personActivities.foldLeft(0.0)(_ + getActivityBenefit(_, attributes))

        finalScore = allDayScore + leavingParkingEventScore + activityScore
        finalScore = Math.max(finalScore, -100000) // keep scores no further below -100k to keep MATSim happy (doesn't like -Infinity) but knowing
        // that if changes to utility function drive the true scores below -100k, this will need to be replaced with another big number.

        // Write the individual's trip scores to csv
        writeTripScoresToCSV()

        //write generalized link stats to file

        if (modeChoiceCalculator.isInstanceOf[ModeChoiceMultinomialLogit]) {
          registerLinkCosts(this.trips, attributes, modeChoiceCalculator.asInstanceOf[ModeChoiceMultinomialLogit])
        }
      }

      private def getActivityBenefit(
        activity: Activity,
        attributes: AttributesOfIndividual
      ): Double = {
        beamServices.beamScenario.destinationChoiceModel.getActivityUtility(activity, attributes)
      }

      private def getRealStartEndTime(
        activity: Activity
      ): (Double, Double) = {
        val start = if (activity.getStartTime > 0) { activity.getStartTime } else { 0 }
        val end = if (activity.getEndTime > 0) { activity.getEndTime } else { 3600 * 24 }
        (start, end)
      }

      override def handleActivity(activity: Activity): Unit = {}

      override def getScore: Double = finalScore

      /**
        * Writes each individual's trip score to a csv file
        */
      private def writeTripScoresToCSV(): Unit = {
        val attributes =
          person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]
        val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory(attributes)
        //For each trip , generate the data to be written to the output file
        val tripScoreData = trips.zipWithIndex map { tripWithIndex =>
          val (trip, tripIndex) = tripWithIndex
          val personId = person.getId.toString
          val tripPurpose = person.getSelectedPlan.getPlanElements.asScala
            .filter(_.isInstanceOf[Activity])
            .map(_.asInstanceOf[Activity])
            .lift(tripIndex + 1)
          val departureTime = trip.legs.headOption.map(_.beamLeg.startTime.toString).getOrElse("")
          val totalTravelTimeInSecs = trip.totalTravelTimeInSecs
          val mode = trip.tripClassifier
          val score = modeChoiceCalculator.utilityOf(trip, attributes, tripPurpose)
          val cost = trip.costEstimate
          s"$personId,$tripIndex,$departureTime,$totalTravelTimeInSecs,$mode,$cost,$score"
        } mkString "\n"

        // save the generated output data to an in-memory map , to be written at the end of the iteration
        setPersonScore(person.getId.toString, tripScoreData)
      }

      /**
        * Writes generalized link stats to csv file.
        */
      private def registerLinkCosts(
        trips: Seq[EmbodiedBeamTrip],
        attributes: AttributesOfIndividual,
        modeChoiceMultinomialLogit: ModeChoiceMultinomialLogit
      ): Unit = {
        // Consider only trips that start between the given time range (specified in the scenario config)
        val startTime = beamConfig.beam.outputs.generalizedLinkStats.startTime
        val endTime = beamConfig.beam.outputs.generalizedLinkStats.endTime
        val filteredTrips = trips filter { t =>
          t.legs.headOption.exists(bleg => bleg.beamLeg.startTime >= startTime && bleg.beamLeg.startTime <= endTime)
        }
        filteredTrips.zipWithIndex foreach {
          case (trip, tripIndex) =>
            val tripCost = trip.costEstimate
            val tripDistance = trip.legs.map(_.beamLeg.travelPath.distanceInM).sum
            val destinationActivity = person.getSelectedPlan.getPlanElements.asScala
              .filter(_.isInstanceOf[Activity])
              .map(_.asInstanceOf[Activity])
              .lift(tripIndex + 1)
            trip.legs.foreach { leg =>
              val linksAndTravelTimes = leg.beamLeg.travelPath.linkIds.zip(leg.beamLeg.travelPath.linkTravelTime)
              linksAndTravelTimes.foreach {
                case (linkId, linkTT) =>
                  val (existingAverageTravelTime, observedTravelTimesCount) =
                    linkAverageTravelTimes.getOrElse(linkId, 0D -> 0)
                  val (existingAverageCost, observedCostsCount) =
                    linkAverageCosts.getOrElse(linkId, 0D -> 0)
                  val (existingAverageGeneralizedCost, observedGeneralizedCostsCount) =
                    linkAverageGeneralizedCosts.getOrElse(linkId, 0D -> 0)
                  val (existingAverageGeneralizedTime, observedGeneralizedTimesCount) =
                    linkAverageGeneralizedTimes.getOrElse(linkId, 0D -> 0)
                  val generalizedLinkTime = attributes.getGeneralizedTimeOfLinkForMNL(
                    (linkId, math.round(linkTT.toFloat)),
                    leg.beamLeg.mode,
                    modeChoiceMultinomialLogit,
                    beamServices,
                    leg.beamVehicleTypeId,
                    destinationActivity,
                    leg.isRideHail,
                    leg.isPooledTrip
                  )

                  val linkCost = tripCost / tripDistance * beamServices.networkHelper.getLink(linkId).get.getLength
                  val generalizedLinkCost = attributes.getVOT(generalizedLinkTime) + linkCost

                  val newTravelTimesCount = observedTravelTimesCount + 1
                  val newTravelTimeAverage = ((existingAverageTravelTime * observedTravelTimesCount) + linkTT) / newTravelTimesCount
                  linkAverageTravelTimes
                    .put(linkId, newTravelTimeAverage -> newTravelTimesCount)

                  val newCostsCount = observedCostsCount + 1
                  val newCostsAverage = ((existingAverageCost * observedCostsCount) + linkCost) / newCostsCount
                  linkAverageCosts
                    .put(linkId, newCostsAverage -> newCostsCount)

                  val newGeneralizedCostsCount = observedGeneralizedCostsCount + 1
                  val newGeneralizedCostsAverage = ((existingAverageGeneralizedCost * observedGeneralizedCostsCount) + generalizedLinkCost) / newGeneralizedCostsCount
                  linkAverageGeneralizedCosts
                    .put(linkId, newGeneralizedCostsAverage -> newGeneralizedCostsCount)

                  val newGeneralizedTimesCount = observedGeneralizedTimesCount + 1
                  val newGeneralizedTimesAverage = ((existingAverageGeneralizedTime * observedGeneralizedTimesCount) + generalizedLinkTime * 3600) / newGeneralizedTimesCount
                  linkAverageGeneralizedTimes
                    .put(linkId, newGeneralizedTimesAverage -> newGeneralizedTimesCount) // NOTE: Store in seconds, not hours
              }
            }
        }
      }
    }
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    writePersonScoreDataToFile(event)
    writeGeneralizedLinkStatsDataToFile(event)
    reset()
  }

  /**
    * Resets the scores
    */
  def reset(): Unit = {
    personTripScores.clear()
    linkAverageTravelTimes.clear()
    linkAverageCosts.clear()
    linkAverageGeneralizedCosts.clear()
    linkAverageGeneralizedTimes.clear()
  }

  private def writePersonScoreDataToFile(event: IterationEndsEvent): Unit = {
    val interval = beamConfig.beam.debug.agentTripScoresInterval
    if (interval > 0 && event.getIteration % interval == 0) {
      val fileHeader = "personId,tripIdx,departureTime,totalTravelTimeInSecs,mode,cost,score"
      // Output file relative path
      val filePath = event.getServices.getControlerIO.getIterationFilename(
        beamServices.matsimServices.getIterationNumber,
        BeamScoringFunctionFactory.agentTripScoreFileBaseName + ".csv.gz"
      )
      // get the data stored in an in memory map
      val scoresData = getPersonScores.values mkString "\n"
      //write the data to an output file
      FileUtils.writeToFile(filePath, Some(fileHeader), scoresData, None)
    }
  }

  private def writeGeneralizedLinkStatsDataToFile(event: IterationEndsEvent): Unit = {
    val linkStatsInterval = beamConfig.beam.outputs.generalizedLinkStatsInterval
    if (linkStatsInterval > 0 && event.getIteration % linkStatsInterval == 0) {
      val fileHeader = "linkId,travelTime,cost,generalizedTravelTime,generalizedCost"
      // Output file relative path
      val filePath = event.getServices.getControlerIO.getIterationFilename(
        beamServices.matsimServices.getIterationNumber,
        BeamScoringFunctionFactory.linkStatsFileBaseName + ".csv.gz"
      )
      val uniqueLinkIds = linkAverageTravelTimes.keySet
      val data = (for (linkId <- uniqueLinkIds) yield {
        val avgTravelTime =
          linkAverageTravelTimes.get(linkId).map(_._1.toString).getOrElse("")
        val avgCost = linkAverageCosts.get(linkId).map(_._1.toString).getOrElse("")
        val generalizedTravelTime =
          linkAverageGeneralizedTimes.get(linkId).map(_._1.toString).getOrElse("")
        val generalizedCost =
          linkAverageGeneralizedCosts.get(linkId).map(_._1.toString).getOrElse("")
        s"$linkId,$avgTravelTime,$avgCost,$generalizedTravelTime,$generalizedCost"
      }) mkString "\n"
      //write the data to an output file
      FileUtils.writeToFile(filePath, Some(fileHeader), data, None)
    }
  }

  override def update(observable: BeamConfigChangesObservable, updatedBeamConfig: BeamConfig): Unit = {
    this.beamConfig = updatedBeamConfig
  }
}

/**
  * A companion object for the BeamScoringFunctionFactory class
  */
object BeamScoringFunctionFactory extends OutputDataDescriptor {

  final val agentTripScoreFileBaseName = "agentTripScores"
  final val linkStatsFileBaseName = "generalizedLinkStats"

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions(
    ioController: OutputDirectoryHierarchy
  ): java.util.List[OutputDataDescription] = {
    val filePath = ioController.getIterationFilename(0, agentTripScoreFileBaseName + ".csv.gz")
    val outputDirPath: String = ioController.getOutputPath
    val relativePath: String = filePath.replace(outputDirPath, "")
    val outputDataDescription =
      OutputDataDescription(classOf[BeamScoringFunctionFactory].getSimpleName.dropRight(1), relativePath, "", "")
    List(
      "personId"              -> "Id of the person in the scenario",
      "tripIdx"               -> "Index of the current trip among all planned trips of the person",
      "departureTime"         -> "Time of departure of the person",
      "totalTravelTimeInSecs" -> "The duration of the entire trip in seconds",
      "mode"                  -> "Trip mode based on all legs within the trip",
      "cost"                  -> "Estimated cost incurred for the entire trip",
      "score"                 -> "Trip score calculated based on the scoring function"
    ).map {
      case (header, description) =>
        outputDataDescription.copy(field = header, description = description)
    }.asJava
  }

}
