package beam.agentsim.agents

import akka.actor.{Actor, ActorRef, Props}
import beam.agentsim.agents.TaxiAgent.PickupCustomer
import beam.agentsim.agents.TaxiManager._
import beam.sim.BeamServices
import org.geotools.geometry.DirectPosition2D
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
  * BEAM
  */
object TaxiManager {
  def props(services: BeamServices) = Props(classOf[TaxiManager],services)

  val log = LoggerFactory.getLogger(classOf[TaxiManager])
  val taxiToCoord = new mutable.HashMap[ActorRef,Coord]()

  case class TaxiInquiry(location: Coord, radius: Double)
  case class TaxiInquiryResponse(timesToCustomer: Vector[Double]) // TODO add fare

  case class ReserveTaxi(location: Coord)
  case class ReserveTaxiConfirmation(taxi: Option[ActorRef], timeToCustomer: Double)

  case class RegisterTaxiAvailable(ref: ActorRef, location: Coord )
  case class RegisterTaxiUnavailable(ref: ActorRef, location: Coord )
  case object TaxiUnavailableAck
  case object TaxiAvailableAck

}
class TaxiManager(theServices: BeamServices)  extends Actor {
  import scala.collection.JavaConverters._
  val services = theServices
  val bbBuffer = 100000
  val travelTimeToCustomerInSecPerMeter = 0.075 // ~30 mph
  val quadTree = new QuadTree[ActorRef](services.bbox.minX - bbBuffer,services.bbox.minY - bbBuffer,services.bbox.maxX + bbBuffer,services.bbox.maxY + bbBuffer)
  val transform = CRS.findMathTransform(CRS.decode("EPSG:4326", true), CRS.decode("EPSG:26910", true), false)

  override def receive: Receive = {
    case RegisterTaxiAvailable(ref: ActorRef, location: Coord ) =>
      // register
      val i = 0
      val pos = new DirectPosition2D(location.getX, location.getY)
      val posTransformed = new DirectPosition2D(location.getX,location.getY)
      if (location.getX <= 180.0 & location.getX >= -180.0 & location.getY > -90.0 & location.getY < 90.0) {
        transform.transform(pos, posTransformed)
      }
      quadTree.put(posTransformed.x,posTransformed.y,ref)
      taxiToCoord += (ref -> new Coord(posTransformed.x,posTransformed.y))
      sender ! TaxiAvailableAck

    case TaxiInquiry(location: Coord, radius: Double) =>
      val response = quadTree.getDisk(location.getX, location.getY, radius).asScala.toVector.map(ref =>
        travelTimeToCustomerInSecPerMeter * math.sqrt(math.pow(taxiToCoord(ref).getX - location.getX,2.0) +
          math.pow(taxiToCoord(ref).getX - location.getX,2.0)))
      sender ! TaxiInquiryResponse(response)

    case ReserveTaxi(location: Coord) =>
      var chosenTaxi : Option[ActorRef] = None
      var timeToCustomer = 0.0
      //TODO there's probably a threshold above which no match is made
      if(quadTree.size()>0){
        val ref = quadTree.getClosest(location.getX, location.getY)
        ref ! PickupCustomer
        val coord : Coord = taxiToCoord(ref)
        timeToCustomer = travelTimeToCustomerInSecPerMeter * math.sqrt(math.pow(coord.getX - location.getX,2.0) + math.pow(coord.getY - location.getY,2.0))
        quadTree.remove(coord.getX,coord.getY,ref)
        taxiToCoord -= ref
        chosenTaxi = Some(ref)
      }
      sender ! ReserveTaxiConfirmation(chosenTaxi, timeToCustomer)

    case msg =>
      log.info(s"unknown message received by TaxiManager $msg")
  }
}
