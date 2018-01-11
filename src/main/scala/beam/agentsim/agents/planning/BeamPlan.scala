package beam.agentsim.agents.planning

import java.{lang, util}

import beam.agentsim.agents.planning.Startegy.Strategy
import org.matsim.api.core.v01.population._
import org.matsim.utils.objectattributes.attributable.Attributes

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * BeamPlan
  *
  * A BeamPlan extends a MATSim [[Plan]], which is a collection of [[PlanElement]] objects ([[Activity]] and
  * [[Leg]]). What BEAM adds to the Plan are additional PlanElement types that allow further organization, where Legs
  * are grouped into Trips (one Trip is a Leg/Activity pair), which are grouped into Tours. A special case Tour and Trip
  * is the first Activity of the day, which contains only that activity.
  *
  * In addition, a BeamPlan contains mappings from PlanElements to Strategies. A Strategy
  * is essentially a label that is assigned during initialization or Replanning and used during the MobSim
  * to influence within-day Agent behavior. Strategies can be mapped to a Plan at any level (e.g. the whole plan,
  * to a tour, a trip, etc.) but can be looked up at any level as well (allowing a Tour to have a strategy and a
  * lookup on a Leg within that tour will yield that strategy).
  *
  */
object BeamPlan {
  def apply(matsimPlan: Plan): BeamPlan = {
    val beamPlan = new BeamPlan
    beamPlan.setPerson(matsimPlan.getPerson)
    matsimPlan.getPlanElements.asScala.foreach { pe =>
      if (pe.isInstanceOf[Activity]) {
        beamPlan.addActivity(pe.asInstanceOf[Activity])
      } else {
        beamPlan.addLeg(pe.asInstanceOf[Leg])
      }
    }
    beamPlan.setScore(matsimPlan.getScore)
    beamPlan.setType(matsimPlan.getType)
    beamPlan.createToursFromMatsimPlan
    beamPlan
  }
}

class BeamPlan extends Plan{


  // Implementation of Legacy Interface
  private var person: Person = _
  private var actsLegs: Vector[PlanElement] = Vector()
  private var actsLegToTrip: mutable.Map[PlanElement,Trip] = mutable.Map()
  private var score : Double = Double.NaN
  private var planType: String = ""

  // Beam-Specific members
  var tours: Vector[Tour] = Vector()
  private var strategies: mutable.Map[PlanElement,mutable.Map[Class[_ <: Strategy],Strategy]] = mutable.Map()

  //////////////////////////////////////////////////////////////////////
  // Beam-Specific methods
  //////////////////////////////////////////////////////////////////////
  def trips: Vector[Trip] = tours.flatMap(_.trips)
  def activities: Vector[Activity] = tours.flatMap(_.trips.map(_.activity))
  def legs: Vector[Leg] = tours.flatMap(_.trips.map(_.leg)).flatMap(leg => leg)
  def createToursFromMatsimPlan = {
    tours = Vector()
    var nextTour = new Tour
    var nextLeg : Option[Leg] = None
    var nextAct : Option[Activity] = None
    actsLegs.foreach{planElement =>
      planElement match {
        case activity: Activity =>
          var nextTrip = new Trip(activity, nextLeg, nextTour)
          nextTour.addTrip(nextTrip)
          if(activity.getType.equalsIgnoreCase("home")){
            tours = tours :+ nextTour
            nextTour = new Tour
          }
        case leg: Leg =>
          nextLeg = Some(leg)
      }
    }
    if(nextTour.trips.nonEmpty)tours = tours :+ nextTour
    indexBeamPlan
  }

  def indexTrip(trip: Trip)= {
    actsLegToTrip.put(trip.activity,trip)
    trip.leg match {
      case Some(leg) =>
        actsLegToTrip.put(leg,trip)
      case None =>
    }
  }

  def indexBeamPlan = {
    tours.foreach( tour => tour.trips.foreach(indexTrip(_)))
  }

  def putStrategy(planElement: PlanElement, strategy: Strategy): Unit = {
    if(!strategies.contains(planElement)){
      strategies.put(planElement,mutable.Map[Class[_ <: Strategy],Strategy]())
    }
    strategies.get(planElement).get.put(strategy.getClass,strategy)

    planElement match {
      case tour: Tour =>
        tour.trips.foreach( trip => putStrategy(trip, strategy) )
      case trip: Trip =>
        putStrategy(trip.activity, strategy)
        trip.leg.foreach(theLeg => putStrategy(theLeg, strategy))
      case _ =>
        // Already dealt with Acts and Legs
    }
  }
  def getStrategy(planElement: PlanElement, forClass: Class[_ <: Strategy]): Option[Strategy] = {
    strategies.getOrElse(planElement, mutable.Map()).get(forClass)
  }
  def getTripContaining(planElement: PlanElement): Trip = {
    planElement match{
      case tour: Tour =>
        throw new RuntimeException("getTripContaining is only for finding the parent trip to a plan element, not a child.")
      case actOrLeg: PlanElement =>
        actsLegToTrip.get(actOrLeg) match {
          case Some(trip) =>
            trip
          case None =>
            throw new RuntimeException(s"Trip not found for plan element ${planElement}.")
        }
    }
  }
  def getTourContaining(planElement: PlanElement): Tour ={
    getTripContaining(planElement).parentTour
  }
  def isLastElementInTour(planElement: PlanElement): Boolean = {
    val tour = getTourContaining(planElement)
    planElement match{
      case act: Activity =>
        tour.trips.last.activity == act
      case leg: Leg =>
        tour.trips.last.leg.isDefined && tour.trips.last.leg.get == leg
      case trip: Trip =>
        tour.trips.last == trip
      case _ =>
        throw new RuntimeException(s"Unexpected PlanElement ${planElement}.")
    }
  }
  def tourIndexOfElement(planElement: PlanElement): Int = {
    (for (tour <- tours.zipWithIndex if (tour._1 == getTourContaining(planElement))) yield (tour._2)).head
  }


  //////////////////////////////////////////////////////////////////////
  // Supporting legacy interface
  //////////////////////////////////////////////////////////////////////

  override def getPerson: Person = this.person
  override def setPerson(newPerson: Person): Unit = {
    this.person = newPerson
  }
  override def getScore: java.lang.Double = score

  override def getType: String = planType
  override def setType(newType: String): Unit = {
    planType = newType
  }

  override def getPlanElements: java.util.List[PlanElement] = actsLegs.asJava

  override def addLeg(leg: Leg): Unit = {
    tours.isEmpty match {
      case true =>
        actsLegs = actsLegs :+ leg
      case false =>
        throw new RuntimeException("For compatibility with MATSim, a BeamPlan only supports addLeg during initialization " +
          "but not after the BeamPlan plan has been created.")
    }
  }
  override def addActivity(act: Activity): Unit = {
    tours.isEmpty match {
      case true =>
        actsLegs = actsLegs :+ act
      case false =>
        throw new RuntimeException("For compatibility with MATSim, a BeamPlan only supports addActivity during initialization " +
          "but not after the BeamPlan plan has been created.")
    }
  }

  override def setScore(newScore: lang.Double) = score = newScore

  override def getCustomAttributes = new util.HashMap[String, AnyRef]()

  override def getAttributes = new Attributes

  override def toString: String = {
    var scoreString = "undefined"
    if (this.getScore != null) scoreString = this.getScore.toString
    var personIdString = "undefined"
    if (this.getPerson != null) personIdString = this.getPerson.getId.toString
    "[score=" + scoreString + "]" + //				"[selected=" + PersonUtils.isSelected(this) + "]" +
      "[nof_acts_legs=" + getPlanElements.size + "]" + "[type=" + planType+ "]" + "[personId=" + personIdString + "]"
  }
}
