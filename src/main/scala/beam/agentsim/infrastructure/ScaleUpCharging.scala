package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetwork.ChargingVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.{activityTypeStringToEnum, ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.utils.BeamVehicleUtils.toPowerInKW
import beam.utils.MathUtils.roundUniformly
import beam.utils.{MathUtils, VehicleIdGenerator}
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.language.postfixOps
import scala.util.Random

trait ScaleUpCharging extends {
  this: ChargingNetworkManager =>
  import ScaleUpCharging._

  private lazy val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
  private lazy val mersenne: MersenneTwister = new MersenneTwister(beamConfig.matsim.modules.global.randomSeed)
  private lazy val timeStepByHour = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds / 3600.0
  private lazy val virtualParkingInquiries: TrieMap[Int, ParkingInquiry] = TrieMap()
  private lazy val vehicleRequests = mutable.HashMap.empty[(Id[TAZ], ParkingActivityType), List[VehicleRequestInfo]]

  private lazy val scaleUpFactors: Map[ParkingActivityType, Double] = {
    if (!cnmConfig.scaleUp.enabled) Map()
    else {
      Map(
        ParkingActivityType.Home     -> cnmConfig.scaleUp.expansionFactor_home_activity,
        ParkingActivityType.Work     -> cnmConfig.scaleUp.expansionFactor_work_activity,
        ParkingActivityType.Charge   -> cnmConfig.scaleUp.expansionFactor_charge_activity,
        ParkingActivityType.Wherever -> cnmConfig.scaleUp.expansionFactor_wherever_activity
      )
    }
  }

//  lazy val nonSampledVehicle: Map[Id[BeamVehicle], BeamVehicle] = {
//    val vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType] = readBeamVehicleTypeFile(beamConfig)
//    var (allVehicles, _) = readVehiclesFile(
//      beamConfig.beam.agentsim.agents.vehicles.vehiclesFilePath,
//      vehicleTypes,
//      beamConfig.matsim.modules.global.randomSeed,
//      VehicleManager.AnyManager.managerId
//    )
//    getBeamServices.matsimServices.getScenario.getHouseholds.getHouseholds
//      .values()
//      .asScala
//      .flatMap(_.getVehicleIds.asScala.map { vehicleId =>
//        allVehicles = allVehicles - BeamVehicle.createId(vehicleId)
//      })
//    allVehicles
//  }

  private lazy val defaultScaleUpFactor: Double =
    if (!cnmConfig.scaleUp.enabled) 1.0 else cnmConfig.scaleUp.expansionFactor_wherever_activity

  override def loggedReceive: Receive = {
    case t @ TriggerWithId(PlanParkingInquiryTrigger(_, requestId), triggerId) =>
      log.debug(s"Received parking response: $t")
      virtualParkingInquiries.get(requestId) match {
        case Some(inquiry) => self ! inquiry
        case _             => log.error(s"Something is broken in ScaleUpCharging. Request $requestId has not been found")
      }
      sender ! CompletionNotice(triggerId)
    case t @ TriggerWithId(PlanChargingUnplugRequestTrigger(tick, beamVehicle, requestId), triggerId) =>
      log.debug(s"Received parking response: $t")
      self ! ChargingUnplugRequest(tick, beamVehicle, triggerId)
      virtualParkingInquiries.remove(requestId)
    case response @ ParkingInquiryResponse(stall, requestId, triggerId) =>
      log.debug(s"Received parking response: $response")
      virtualParkingInquiries.get(requestId) match {
        case Some(parkingInquiry) if stall.chargingPointType.isDefined =>
          log.debug(s"parking inquiry with requestId $requestId returned a stall with charging point.")
          val beamVehicle = parkingInquiry.beamVehicle.get
          self ! ChargingPlugRequest(
            parkingInquiry.destinationUtm.time,
            beamVehicle,
            stall,
            parkingInquiry.personId.map(Id.create(_, classOf[Person])).getOrElse(Id.create("", classOf[Person])),
            triggerId,
            NotApplicable,
            None
          )
          val endTime = (parkingInquiry.destinationUtm.time + parkingInquiry.parkingDuration).toInt
          getScheduler ! ScheduleTrigger(
            PlanChargingUnplugRequestTrigger(endTime, beamVehicle, parkingInquiry.requestId),
            self
          )
        case Some(_) if stall.chargingPointType.isEmpty =>
          log.debug(s"parking inquiry with requestId $requestId returned a NoCharger stall")
        case _ =>
          log.warning(s"inquiryMap does not have this requestId $requestId that returned stall $stall")
      }
    case reply @ StartingRefuelSession(_, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ EndingRefuelSession(_, _, triggerId) =>
      log.debug(s"Received parking response: $reply")
      getScheduler ! CompletionNotice(triggerId)
    case reply @ WaitingToCharge(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ UnhandledVehicle(_, _, triggerId) =>
      log.debug(s"Received parking response: $reply")
      getScheduler ! CompletionNotice(triggerId)
    case reply @ UnpluggingVehicle(_, _, triggerId) =>
      log.debug(s"Received parking response: $reply")
      getScheduler ! CompletionNotice(triggerId)
  }

  /**
    * Next Time poisson
    * @param rate rate of charging event
    * @return
    */
  private def nextTimeStepUsingPoissonProcess(rate: Double): Double =
    3600.0 * (-Math.log(1.0 - rand.nextDouble()) / rate)

  /**
    * @param timeBin current time bin
    * @param triggerId trigger di for the scheduler
    * @return
    */
  protected def simulateEventsIfScalingEnabled(timeBin: Int, triggerId: Long): Vector[ScheduleTrigger] = {
    val results = vehicleRequests
      .groupBy(_._1._1)
      .par
      .mapValues(_.map { case ((_, parkingActivityType), data) =>
        val numObservation = data.size
        val totPowerInKW = data.map(x => toPowerInKW(x.energyToChargeInJoule, x.durationToChargeInSec)).sum
        val listDur = data.map(_.durationToChargeInSec)
        val totDurationInSec = listDur.sum
        val meanDur: Double = listDur.sum / numObservation.toDouble
        val varianceDur: Double = listDur.map(d => d - meanDur).map(t => t * t).sum / numObservation
        val listSOC = data.map(_.stateOfCharge)
        val meanSOC: Double = listSOC.sum / numObservation.toDouble
        val varianceSOC: Double = listSOC.map(soc => soc - meanSOC).map(t => t * t).sum / numObservation
        val listEnergy = data.map(_.energyToChargeInJoule)
        val meanEnergy: Double = listEnergy.sum / listEnergy.size.toDouble
        val varianceEnergy: Double = listEnergy.map(energy => energy - meanEnergy).map(t => t * t).sum / numObservation
        val pmfActivityType =
          data
            .groupBy(_.activityType)
            .map { case (activityType, elems) =>
              new CPair[String, java.lang.Double](activityType, elems.size.toDouble)
            }
            .toVector
        val pmfVehicleTypeInfo = data
          .groupBy(record => (record.vehicleType, record.vehicleAlias, record.reservedFor))
          .map { case ((vehicleType, vehicleAlias, reservedFor), elems) =>
            new CPair[VehicleTypeInfo, java.lang.Double](
              VehicleTypeInfo(vehicleType, vehicleAlias, reservedFor),
              elems.size.toDouble
            )
          }
          .toVector
        val vehicleInfoSummary = VehicleInfoSummary(
          numObservation = numObservation,
          totPowerInKW = totPowerInKW,
          totDurationInSec = totDurationInSec,
          meanDuration = meanDur,
          varianceDuration = varianceDur,
          meanSOC = meanSOC,
          varianceSOC = varianceSOC,
          meanEnergy = meanEnergy,
          varianceEnergy = varianceEnergy,
          new EnumeratedDistribution[VehicleTypeInfo](mersenne, pmfVehicleTypeInfo.asJava),
          new EnumeratedDistribution[String](mersenne, pmfActivityType.asJava)
        )
        parkingActivityType -> (data, vehicleInfoSummary)
      })
      .flatMap { case (tazId, activityType2vehicleInfo) =>
        val partialTriggersAndInquiries = Vector.newBuilder[(ScheduleTrigger, ParkingInquiry)]
        activityType2vehicleInfo.foldLeft((0.0, 0.0, Vector.empty[CPair[ParkingActivityType, java.lang.Double]])) {
          case ((powerAcc, numEventsAcc, pmfAcc), (activityType, (_, dataSummary))) =>
            val scaleUpFactor = scaleUpFactors.getOrElse(activityType, defaultScaleUpFactor) - 1
            val power = scaleUpFactor * dataSummary.totPowerInKW
            val pmf = new CPair[ParkingActivityType, java.lang.Double](activityType, power)
            val numEvents = scaleUpFactor * dataSummary.numObservation
            (powerAcc + power, numEventsAcc + numEvents, pmfAcc :+ pmf)
        } match {
          case (totPowerInKWToSimulate, totNumberOfEvents, pmf) if totPowerInKWToSimulate > 0 =>
            val distribution = new EnumeratedDistribution[ParkingActivityType](mersenne, pmf.asJava)
            val rate = totNumberOfEvents / timeStepByHour
            var cumulatedSimulatedPower = 0.0
            var timeStep = 0
            while (cumulatedSimulatedPower < totPowerInKWToSimulate && timeStep < timeStepByHour * 3600) {
              val (_, summary) = activityType2vehicleInfo(distribution.sample())
              val duration = Math.max(
                summary.getDuration(rand),
                beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds.toInt
              )
              timeStep += roundUniformly(nextTimeStepUsingPoissonProcess(rate), rand).toInt
              val vehicleTypeInfo = summary.vehicleTypeInfoDistribution.sample()
              val soc = summary.meanSOC / 100.0
              val energyToCharge = summary.getEnergy(rand)
              val taz = getBeamServices.beamScenario.tazTreeMap.getTAZ(tazId).get
              val destinationUtm = TAZTreeMap.randomLocationInTAZ(taz, rand)
              val activityType = summary.activityTypeDistribution.sample()
              val reservedFor = vehicleTypeInfo.reservedFor
              val beamVehicle = getBeamVehicle(vehicleTypeInfo, soc)
              val personId = getPerson(beamVehicle.id)
              val startTime = timeBin + timeStep
              val parkingInquiry = ParkingInquiry(
                SpaceTime(destinationUtm, startTime),
                activityType,
                reservedFor,
                Some(beamVehicle),
                None, // remainingTripData
                Some(personId),
                1.0, // valueOfTime
                duration,
                searchMode = ParkingSearchMode.DestinationCharging,
                triggerId = triggerId
              )
              val trigger = ScheduleTrigger(PlanParkingInquiryTrigger(startTime, parkingInquiry.requestId), self)
              cumulatedSimulatedPower += toPowerInKW(energyToCharge, duration)
              partialTriggersAndInquiries += ((trigger, parkingInquiry))
            }
          case _ =>
            log.debug("The observed load is null. Most likely due to vehicles not needing to charge!")
        }
        partialTriggersAndInquiries.result()
      }
      .seq
      .toVector
    results.foreach(x => virtualParkingInquiries.put(x._2.requestId, x._2))
    val triggers = results.map(_._1)
    vehicleRequests.clear()
    triggers
  }

  /**
    * @param chargingVehicle the vehicle that just plugged in
    */
  protected def collectVehicleRequestInfo(chargingVehicle: ChargingVehicle): Unit = {
    if (cnmConfig.scaleUp.enabled && !isVirtualCar(chargingVehicle.vehicle.id)) {
      val vehicle = chargingVehicle.vehicle
      val stall = chargingVehicle.stall
      val vehicleAlias =
        if (vehicle.isRidehail) "rideHail"
        else if (vehicle.isSharedVehicle) "sharedVehicle"
        else "personalVehicle"
      val estimatedChargingDuration = Math.max(
        chargingVehicle.estimatedParkingDuration,
        beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds.toInt
      )
      val (durationToCharge, energyToCharge) =
        chargingVehicle.vehicle.refuelingSessionDurationAndEnergyInJoulesForStall(
          Some(chargingVehicle.stall),
          sessionDurationLimit = Some(estimatedChargingDuration),
          stateOfChargeLimit = None,
          chargingPowerLimit = None
        )
      vehicleRequests.synchronized {
        val key = (stall.tazId, activityTypeStringToEnum(chargingVehicle.activityType))
        vehicleRequests.put(
          key,
          vehicleRequests.getOrElse(key, List.empty) :+ VehicleRequestInfo(
            energyToCharge,
            Math.min(durationToCharge, estimatedChargingDuration),
            100 * Math.min(Math.max(vehicle.getStateOfCharge, 0), 1),
            chargingVehicle.activityType,
            vehicle.beamVehicleType,
            vehicleAlias,
            VehicleManager.getReservedFor(vehicle.vehicleManagerId.get()).getOrElse(VehicleManager.AnyManager)
          )
        )
      }
    }
  }

  /**
    * get Beam Vehicle
    * @param vehicleTypeInfo VehicleTypeInfo
    * @param soc State Of Charge In Double
    * @return
    */
  protected def getBeamVehicle(vehicleTypeInfo: VehicleTypeInfo, soc: Double): BeamVehicle = {
    val powerTrain = new Powertrain(vehicleTypeInfo.vehicleType.primaryFuelConsumptionInJoulePerMeter)
    val nextId = VehicleIdGenerator.nextId
    val beamVehicle = new BeamVehicle(
      Id.create(VIRTUAL_ALIAS + "-" + vehicleTypeInfo.vehicleAlias + "-" + nextId, classOf[BeamVehicle]),
      powerTrain,
      vehicleTypeInfo.vehicleType,
      new AtomicReference(vehicleTypeInfo.reservedFor.managerId),
      randomSeed = rand.nextInt
    )
    beamVehicle.initializeFuelLevels(soc)
    beamVehicle
  }

  /**
    * @param vehicleId vehicle Id
    * @return
    */
  protected def getPerson(vehicleId: Id[BeamVehicle]): Id[Person] = {
    Id.create(vehicleId.toString, classOf[Person])
  }

  /**
    * identify whether the vehicle has been created for scaling up or not
    * @param vehicleId vehicle Id
    * @return
    */
  protected def isVirtualCar(vehicleId: Id[BeamVehicle]): Boolean = isVirtualEntity(vehicleId)

  protected def isVirtualPerson(personId: Id[Person]): Boolean = isVirtualEntity(personId)

  private def isVirtualEntity(entity: Id[_]): Boolean = entity.toString.startsWith(VIRTUAL_ALIAS)
}

object ScaleUpCharging {
  val VIRTUAL_ALIAS: String = "virtual"
  case class PlanParkingInquiryTrigger(tick: Int, requestId: Int) extends Trigger
  case class PlanChargingUnplugRequestTrigger(tick: Int, beamVehicle: BeamVehicle, requestId: Int) extends Trigger

  case class VehicleRequestInfo(
    energyToChargeInJoule: Double,
    durationToChargeInSec: Int,
    stateOfCharge: Double,
    activityType: String,
    vehicleType: BeamVehicleType,
    vehicleAlias: String,
    reservedFor: ReservedFor
  )

  case class VehicleTypeInfo(
    vehicleType: BeamVehicleType,
    vehicleAlias: String,
    reservedFor: ReservedFor
  ) {
    override def toString: String = s"${vehicleType.id.toString}|$vehicleAlias|${reservedFor.toString}"
    override val hashCode: Int = toString.hashCode
  }

  case class VehicleInfoSummary(
    numObservation: Int,
    totPowerInKW: Double,
    totDurationInSec: Int,
    meanDuration: Double,
    varianceDuration: Double,
    meanSOC: Double,
    varianceSOC: Double,
    meanEnergy: Double,
    varianceEnergy: Double,
    vehicleTypeInfoDistribution: EnumeratedDistribution[VehicleTypeInfo],
    activityTypeDistribution: EnumeratedDistribution[String]
  ) {
    def getDuration(rand: Random): Int = logNormalDistribution(meanDuration, varianceDuration, rand).toInt
    def getSOC(rand: Random): Double = logNormalDistribution(meanSOC, varianceSOC, rand)
    def getEnergy(rand: Random): Double = logNormalDistribution(meanEnergy, varianceEnergy, rand)

    private def logNormalDistribution(mean: Double, variance: Double, rand: Random) /* mean and variance of Y */ = {
      val phi = Math.sqrt(variance + (mean * mean))
      val mu = if (mean <= 0) 0.0 else Math.log((mean * mean) / phi) /* mean of log(Y)    */
      val sigma = if (phi <= 0) 0.0 else Math.sqrt(Math.log((phi * phi) / (mean * mean))) /* std dev of log(Y) */
      val x = MathUtils.roundUniformly(Math.max(mu + (rand.nextGaussian() * sigma), 0.0), rand).toInt
      Math.exp(x)
    }
  }
}
