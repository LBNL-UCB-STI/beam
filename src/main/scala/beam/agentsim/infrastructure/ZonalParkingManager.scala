package beam.agentsim.infrastructure

import akka.actor.FSM.Event
import akka.actor.{ActorRef, Props}
import beam.agentsim.Resource._
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.PersonAgent
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse, ParkingStockAttributes}
import beam.agentsim.infrastructure.ParkingStall._
import beam.agentsim.infrastructure.ZonalParkingManager.ParkingAlternative
import beam.router.BeamRouter.Location
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.ObjectAttributes

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.util.Random

class ZonalParkingManager(override val beamServices: BeamServices, val beamRouter: ActorRef,
                          tazTreeMap:TAZTreeMap, parkingStockAttributes: ParkingStockAttributes) extends ParkingManager(tazTreeMap,parkingStockAttributes)
  with HasServices{
  override val resources: mutable.Map[Id[ParkingStall], ParkingStall] = collection.mutable.Map[Id[ParkingStall], ParkingStall]()
  val pooledResources: mutable.Map[StallAttributes,Int] = mutable.Map()
  var stallnum = 0

  //TODO allow specification of parking/charging distribution
  tazTreeMap.tazQuadTree.values().forEach { taz =>
    List(Residential, Workplace, Public).foreach { parkingType =>
      List(Free, FlatFee, Block).foreach { pricingModel =>
        List(NoCharger, Level1, Level2, DCFast, UltraFast).foreach { chargingType =>
          pooledResources.put(StallAttributes(taz.tazId, parkingType, pricingModel, chargingType), 1)
        }
      }
    }
  }
  //TODO read from csv and update map

  // Make a very big pool of NA stalls used to return to agents when there are no alternatives left
  pooledResources.put(StallAttributes(Id.create("NA",classOf[TAZ]),NoOtherExists,FlatFee,NoCharger),Int.MaxValue)


  override def receive: Receive = {
    case RegisterResource(stallId: Id[ParkingStall]) =>
      // For Zonal Parking, stalls are created internally

    case NotifyResourceIdle(stallId: Id[ParkingStall], whenWhere) =>
      // Irrelevant for parking

    case NotifyResourceInUse(stallId: Id[ParkingStall], whenWhere) =>
      // Irrelevant for parking

    case CheckInResource(stallId: Id[ParkingStall], availableIn: Option[SpaceTime]) =>
        val stall = resources.get(stallId).get
        pooledResources.update(stall.attributes, pooledResources(stall.attributes) + 1)
        resources.remove(stall.id)

    case CheckOutResource(_) =>
      // Because the ZonalParkingManager is in charge of deciding which stalls to assign, this should never be received
      throw new RuntimeException("Illegal use of CheckOutResource, ZonalParkingManager is responsible for checking out stalls in fleet.")

    case inquiry@ParkingInquiry(customerId: Id[PersonAgent], customerLocationUtm: Location, destinationUtm: Location,
      activityType: String, valueOfTime: Double, chargingPreference: ChargingPreference, arrivalTime: Long, parkingDuration: Double) =>

      val nearbyTazsWithDistances = findTAZsWithDistances(destinationUtm, 500.0)
      val preferredType = activityType match {
        case act if act.equalsIgnoreCase("home") => Residential
        case act if act.equalsIgnoreCase("work") => Workplace
        case _ => Public
      }

      /*
       * To save time avoiding route calculations, we look for the trivial case: nearest TAZ with activity type matching available parking type.
       */
      val maybeDominantSpot = if(chargingPreference == NoNeed) {
        maybeCreateNewStall(StallAttributes(nearbyTazsWithDistances.head._1.tazId, preferredType, Free, NoCharger),destinationUtm, 0.0)
      }else{
        None
      }

      respondWithStall(maybeDominantSpot match {
        case Some(stall) =>
          stall
        case None =>
          chargingPreference match {
            case NoNeed =>
              selectPublicStall(inquiry,500.0)
            case _ =>
              selectStallWithCharger(inquiry,500.0)
          }
      })
  }

  def maybeCreateNewStall(attrib: StallAttributes,atLocation: Location, withCost: Double): Option[ParkingStall] = {
    if (pooledResources(attrib) > 0) {
      stallnum = stallnum + 1
      Some(new ParkingStall(Id.create(stallnum, classOf[ParkingStall]),attrib,atLocation, withCost))
    } else {
      None
    }
  }

  def respondWithStall(stall: ParkingStall) = {
    resources.put(stall.id,stall)
    pooledResources(stall.attributes) = pooledResources(stall.attributes) - 1
    sender() ! ParkingInquiryResponse(stall)
  }

  // TODO make these distributions more custom to the TAZ and stall type
  def sampleLocationForStall(taz: TAZ, attrib: StallAttributes) = {
    val rand = new Random()
    new Location(taz.coord.getX + rand.nextDouble() * 500.0 - 250.0, taz.coord.getY + rand.nextDouble() * 500.0 - 250.0)
  }

  // TODO make pricing into parameters
  // TODO make Block parking model based off a schedule
  def calculateCost(attrib: StallAttributes, arrivalTime: Long, parkingDuration: Double) = {
    attrib.pricingModel match {
      case Free => 0.0
      case FlatFee => 5.0
      case Block => parkingDuration / 3600.0 * 5.0
    }
  }

  def selectPublicStall(inquiry: ParkingInquiry, searchRadius: Double): ParkingStall = {
    val nearbyTazsWithDistances = findTAZsWithDistances(inquiry.destinationUtm, searchRadius)
    val allOptions: Vector[ParkingAlternative] = nearbyTazsWithDistances.map{ taz =>
      Vector(Free, FlatFee, Block).map{ pricingModel =>
        val attrib = StallAttributes(taz._1.tazId, Public, pricingModel, NoCharger)
        if (pooledResources(attrib) > 0) {
          val stallLoc = sampleLocationForStall(taz._1,attrib)
          val walkingDistance = beamServices.geo.distInMeters(stallLoc,inquiry.destinationUtm)
          val valueOfTimeSpentWalking = walkingDistance / 1.4 / 3600.0 * inquiry.valueOfTime // 1.4 m/s avg. walk
          val cost = calculateCost(attrib, inquiry.arrivalTime, inquiry.parkingDuration)
          Vector(ParkingAlternative(attrib, stallLoc, cost, cost + valueOfTimeSpentWalking))
        }else{
          Vector[ParkingAlternative]()
        }
      }.flatten
    }.flatten
    val chosenStall = allOptions.sortBy(_.rankingWeight).headOption match {
      case Some(alternative) =>
        maybeCreateNewStall(alternative.stallAttributes, alternative.location, alternative.cost)
      case None => None
    }
    // Finally, if no stall found, repeat with larger search distance for TAZs or create one very expensive
    chosenStall match {
      case Some(stall) => stall
      case None =>
        if(searchRadius * 2.0 > ZonalParkingManager.maxSearchRadius){
          stallnum = stallnum + 1
          new ParkingStall(Id.create(stallnum, classOf[ParkingStall]),StallAttributes(Id.create("NA",classOf[TAZ]),NoOtherExists,FlatFee,NoCharger),inquiry.destinationUtm, 1000.0)
        }else{
          selectPublicStall(inquiry, searchRadius * 2.0)
        }
    }

  }

  def findTAZsWithDistances(searchCenter: Location, startRadius: Double): Vector[(TAZ, Double)]  = {
    var nearbyTazs: Vector[TAZ] = Vector()
    var searchRadius = startRadius
    while(nearbyTazs.isEmpty){
      if(searchRadius > ZonalParkingManager.maxSearchRadius){
        throw new RuntimeException("Parking search radius has reached 10,000 km and found no TAZs, possible map projection error?")
      }
      nearbyTazs = tazTreeMap.tazQuadTree.getDisk(searchCenter.getX,searchCenter.getY,searchRadius).asScala.toVector
      searchRadius = searchRadius * 2.0
    }
    nearbyTazs.zip(nearbyTazs.map{taz => beamServices.geo.distInMeters(taz.coord,searchCenter)}).sortBy(_._2)
  }

  def selectStallWithCharger(inquiry: ParkingInquiry, startRadius: Double): ParkingStall = ???

}

object ZonalParkingManager{
  case class ParkingAlternative(stallAttributes: StallAttributes, location: Location, cost: Double, rankingWeight: Double)

  def props(beamServices: BeamServices, beamRouter: ActorRef, tazTreeMap: TAZTreeMap, parkingStockAttributes: ParkingStockAttributes): Props = {
    Props(new ZonalParkingManager(beamServices, beamRouter, tazTreeMap, parkingStockAttributes))
  }

  val maxSearchRadius = 10e6
}























