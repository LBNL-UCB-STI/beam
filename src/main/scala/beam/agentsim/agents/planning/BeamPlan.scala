package beam.agentsim.agents.planning

import java.{lang, util}

import org.matsim.api.core.v01.population._
import org.matsim.core.population.PopulationUtils
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
  * is essentially a key that is assigned during initialization or Replanning and used during the MobSim
  * to influence within-day Agent behavior. Strategies can be mapped to a Plan at any level (e.g. the whole plan,
  * to a tour, a trip, etc.) but can be looked up at any level as well (allowing a Tour to have a strategy and a
  * lookup on a Leg within that tour will yield that strategy).
  */
object BeamPlan {

  def apply(matsimPlan: Plan): BeamPlan = {
    val beamPlan = new BeamPlan
    beamPlan.setPerson(matsimPlan.getPerson)
    matsimPlan.getPlanElements.asScala.foreach {
      case activity: Activity =>
        beamPlan.addActivity(activity)
      case leg: Leg =>
        beamPlan.addLeg(leg)
    }
    beamPlan.setScore(matsimPlan.getScore)
    beamPlan.setType(matsimPlan.getType)
    beamPlan.createToursFromMatsimPlan()
    beamPlan
  }

  def addOrReplaceLegBetweenActivities(
    plan: Plan,
    leg: Leg,
    originActivity: Activity,
    destinationActivity: Activity
  ): Plan = {
    val newPlanElements = plan.getPlanElements.asScala
      .sliding(2)
      .flatMap { elems =>
        var outputElems = List(elems.head)
        if (elems.size == 2) {
          elems.head match {
            case headActivity: Activity if headActivity.equals(originActivity) =>
              elems.last match {
                case lastActivity: Activity if lastActivity.equals(destinationActivity) =>
                  outputElems = outputElems :+ leg.asInstanceOf[PlanElement]
                case _: Leg =>
                  outputElems = outputElems :+ leg.asInstanceOf[PlanElement]
                case _ =>
              }
            case _: Leg
                if elems.last.isInstanceOf[Activity] &&
                  elems.last.asInstanceOf[Activity].equals(destinationActivity) =>
              outputElems = List()
            case _ =>
          }
        }
        outputElems
      }
      .toList :+ plan.getPlanElements.asScala.last
    val newPlan = PopulationUtils.createPlan()
    newPlanElements.foreach {
      case a: Activity =>
        newPlan.addActivity(a)
      case l: Leg =>
        newPlan.addLeg(l)
    }
    newPlan.setPerson(plan.getPerson)
    newPlan.setType(plan.getType)
    newPlan.getAttributes.putAttribute("modality-style", plan.getAttributes.getAttribute("modality-style"))
    newPlan.setScore(plan.getScore)
    newPlan
  }
}

class BeamPlan extends Plan {

  //////////////////////////////////////////////////////////////////////
  // Beam-Specific methods
  //////////////////////////////////////////////////////////////////////
  lazy val trips: Array[Trip] = tours.flatMap(_.trips)
  lazy val activities: Array[Activity] = tours.flatMap(_.trips.map(_.activity))
  lazy val legs: Array[Leg] = tours.flatMap(_.trips.map(_.leg)).flatten
  private val actsLegToTrip: mutable.Map[PlanElement, Trip] = mutable.Map()

  // Beam-Specific members
  var tours: Array[Tour] = Array()
  // Implementation of Legacy Interface
  private var person: Person = _
  private var actsLegs: Vector[PlanElement] = Vector()
  private var score: Double = Double.NaN
  private var planType: String = ""

  def createToursFromMatsimPlan(): Unit = {
    val toursBuilder = mutable.ArrayBuilder.make[Tour]()
    var nextTour = new Tour
    var nextLeg: Option[Leg] = None
    actsLegs.foreach {
      case activity: Activity =>
        val nextTrip = Trip(activity, nextLeg, nextTour)
        nextTour.addTrip(nextTrip)
        if (activity.getType.equalsIgnoreCase("home")) {
          toursBuilder += nextTour
          nextTour = new Tour
        }
      case leg: Leg =>
        nextLeg = Some(leg)
    }
    if (nextTour.trips.nonEmpty) toursBuilder += nextTour
    tours = toursBuilder.result()
    indexBeamPlan()
  }

  def indexTrip(trip: Trip): Unit = {
    actsLegToTrip.put(trip.activity, trip)
    trip.leg match {
      case Some(leg) =>
        actsLegToTrip.put(leg, trip)
      case None =>
    }
  }

  def indexBeamPlan(): Unit = {
    tours.foreach(tour => tour.trips.foreach(indexTrip))
  }

  def isLastElementInTour(planElement: PlanElement): Boolean = {
    val tour = getTourContaining(planElement)
    planElement match {
      case act: Activity =>
        tour.trips.last.activity == act
      case leg: Leg =>
        tour.trips.last.leg.isDefined && tour.trips.last.leg.get == leg
      case trip: Trip =>
        tour.trips.last == trip
      case _ =>
        throw new RuntimeException(s"Unexpected PlanElement $planElement.")
    }
  }

  def tourIndexOfElement(planElement: PlanElement): Int = {
    (for (tour <- tours.zipWithIndex if tour._1 == getTourContaining(planElement))
      yield tour._2).head
  }

  def getTourContaining(planElement: PlanElement): Tour = {
    getTripContaining(planElement).parentTour
  }

  def getTripContaining(planElement: PlanElement): Trip = {
    planElement match {
      case _: Tour =>
        throw new RuntimeException(
          "getTripContaining is only for finding the parent trip to a plan element, not a child."
        )
      case actOrLeg: PlanElement =>
        actsLegToTrip.get(actOrLeg) match {
          case Some(trip) =>
            trip
          case None =>
            throw new RuntimeException(s"Trip not found for plan element $planElement.")
        }
    }
  }

  //////////////////////////////////////////////////////////////////////
  // Supporting legacy interface
  //////////////////////////////////////////////////////////////////////

  override def getType: String = planType

  override def setType(newType: String): Unit = {
    planType = newType
  }

  override def addLeg(leg: Leg): Unit = {
    if (tours.isEmpty) {
      actsLegs = actsLegs :+ leg
    } else {
      throw new RuntimeException(
        "For compatibility with MATSim, a BeamPlan only supports addLeg during initialization " +
        "but not after the BeamPlan plan has been created."
      )
    }
  }

  override def addActivity(act: Activity): Unit = {
    if (tours.isEmpty) {
      actsLegs = actsLegs :+ act
    } else {
      throw new RuntimeException(
        "For compatibility with MATSim, a BeamPlan only supports addActivity during initialization " +
        "but not after the BeamPlan plan has been created."
      )
    }
  }

  override def getCustomAttributes = new util.HashMap[String, AnyRef]()

  override def getAttributes = new Attributes

  override def toString: String = {
    var scoreString = "undefined"
    if (this.getScore != null) scoreString = this.getScore.toString
    var personIdString = "undefined"
    if (this.getPerson != null) personIdString = this.getPerson.getId.toString
    "[score=" + scoreString + "]" + //				"[selected=" + PersonUtils.isSelected(this) + "]" +
    "[nof_acts_legs=" + getPlanElements.size + "]" + "[type=" + planType + "]" + "[personId=" + personIdString + "]"
  }

  override def getPerson: Person = this.person

  override def setPerson(newPerson: Person): Unit = {
    this.person = newPerson
  }

  override def getScore: java.lang.Double = score

  override def setScore(newScore: lang.Double): Unit = score = newScore

  override def getPlanElements: java.util.List[PlanElement] = actsLegs.asJava
}
