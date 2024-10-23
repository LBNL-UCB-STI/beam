package beam.agentsim.agents.planning

import beam.agentsim.agents.planning.BeamPlan.atHome

import java.{lang, util}
import beam.agentsim.agents.planning.Strategy.{Strategy, TourModeChoiceStrategy, TripModeChoiceStrategy}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import beam.router.TourModes.BeamTourMode
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population._
import org.matsim.core.population.PopulationUtils
import org.matsim.utils.objectattributes.attributable.Attributes

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag

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
    matsimPlan.getPlanElements.asScala.headOption match {
      case Some(a1: Activity) => beamPlan.addActivity(a1)
      case _                  =>
    }
    matsimPlan.getPlanElements.asScala.sliding(2).foreach {
      case mutable.Buffer(_: Activity, a2: Activity) =>
        beamPlan.addLeg(PopulationUtils.createLeg(""))
        beamPlan.addActivity(a2)
      case mutable.Buffer(_: Activity, l1: Leg) =>
        beamPlan.addLeg(l1)
      case mutable.Buffer(_: Leg, a1: Activity) =>
        beamPlan.addActivity(a1)
      case _ =>
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
    newPlan.getAttributes.putAttribute(
      "modality-style",
      Option(plan.getAttributes.getAttribute("modality-style")).getOrElse("")
    )
    newPlan.setScore(plan.getScore)
    newPlan
  }

  def atHome(activity: Activity): Boolean = activity.getType.equalsIgnoreCase("home")
}

class BeamPlan extends Plan {

  //////////////////////////////////////////////////////////////////////
  // Beam-Specific methods
  //////////////////////////////////////////////////////////////////////
  lazy val trips: Vector[Trip] = tours.flatMap(_.trips)
  private val actsLegToTrip: mutable.Map[PlanElement, Trip] = mutable.Map()

  private val strategies: mutable.Map[PlanElement, mutable.Map[Class[_ <: Strategy], Strategy]] =
    mutable.Map()
  // Beam-Specific members
  var tours: Vector[Tour] = Vector()
  // Implementation of Legacy Interface
  private var person: Person = _
  private var actsLegs: Vector[PlanElement] = Vector()
  private var score: Double = Double.NaN
  private var planType: String = ""

  lazy val legs: Vector[Leg] = actsLegs flatMap {
    case leg: Leg => Some(leg)
    case _        => None
  }

  lazy val activities: Vector[Activity] = actsLegs flatMap {
    case act: Activity => Some(act)
    case _             => None
  }

  private def getTourIdFromMatsimLeg(legOption: Option[Leg]): Option[Int] = {
    legOption.flatMap(leg => Option(leg.getAttributes.getAttribute("tour_id")).map(_.toString.toInt))
  }

  // partial step for creating nested sub tours
//  private def hasReturningActivities(currentActivity: Activity): Boolean = {
//    val currentIndex = activities.indexOf(currentActivity)
//    if (currentIndex == activities.size - 1) { false }
//    else {
//      activities
//        .slice(activities.indexOf(currentActivity) + 1, activities.size - 1)
//        .exists(_.getCoord == currentActivity.getCoord)
//    }
//  }

  private def getTourModeFromMatsimLeg(leg: Leg): Option[BeamTourMode] = {
    Option(leg.getAttributes.getAttribute("tour_mode")).flatMap(x => BeamTourMode.fromString(x.toString))
  }

  private def getTourVehicleFromMatsimLeg(leg: Leg): Option[Id[BeamVehicle]] = {
    Option(leg.getAttributes.getAttribute("tour_vehicle")).map(x => Id.create(x.toString, classOf[BeamVehicle]))
  }

  def createToursFromMatsimPlan(): Unit = {
    tours = Vector()
    var currentTourIndex = -1
    var currentTour = new Tour(-1)
    var previousLeg: Option[Leg] = None
    // partial step for nested subtours
//    val subTourBaseLocations: mutable.Seq[Coord] = mutable.Seq.empty[Coord]
    actsLegs.sliding(2).foreach {
      case Vector(leg: Leg, _: Activity) =>
        previousLeg = Some(leg)

      case Vector(activity: Activity, leg: Leg) =>
        val nextTrip = Trip(activity, previousLeg, currentTour)
        currentTour.addTrip(nextTrip)
        val startNewTour = (getTourIdFromMatsimLeg(Some(leg)), previousLeg) match {
          case (_, None)                                                                            => true
          case (Some(nextId), currentLeg) if getTourIdFromMatsimLeg(currentLeg).exists(_ != nextId) => true
          case (None, _) if atHome(activity)                                                        => true
          case _                                                                                    => false
        }
        if (startNewTour) {
          currentTourIndex += 1
          currentTour.setTourId(
            getTourIdFromMatsimLeg(previousLeg).getOrElse(currentTourIndex)
          )
          if (!tours.map(_.tourId).contains(currentTour.tourId)) {
            tours = tours :+ currentTour
          }
          val previousTourWithSameId = tours.find(x => getTourIdFromMatsimLeg(Some(leg)).contains(x.tourId))
          currentTour = previousTourWithSameId match {
            case Some(matchedTour) => matchedTour
            case _                 => new Tour(originActivity = Some(activity))
          }
          putStrategy(
            currentTour,
            TourModeChoiceStrategy(getTourModeFromMatsimLeg(leg), getTourVehicleFromMatsimLeg(leg))
          )
        }
      case Vector(onlyActivity: Activity) =>
        val nextTrip = Trip(onlyActivity, previousLeg, currentTour)
        currentTour.addTrip(nextTrip)
      case _ =>
        throw new IllegalArgumentException("Poorly formed input plans")
    }
    actsLegs.lastOption match {
      case Some(lastAct: Activity) =>
        val nextTrip = Trip(lastAct, previousLeg, currentTour)
        currentTour.addTrip(nextTrip)
      case _ =>
    }

    if (currentTour.trips.nonEmpty) {
      currentTourIndex += 1
      currentTour.setTourId(
        getTourIdFromMatsimLeg(previousLeg).getOrElse(currentTourIndex)
      )
      if (!tours.map(_.tourId).contains(currentTour.tourId)) {
        tours = tours :+ currentTour
      }
    }
    indexBeamPlan()
    actsLegs.foreach {
      case l: Leg =>
        putStrategy(actsLegToTrip(l), TripModeChoiceStrategy(BeamMode.fromString(l.getMode)))
      case _ =>
    }
  }

  private def indexTrip(trip: Trip): Unit = {
    actsLegToTrip.put(trip.activity, trip)
    trip.leg match {
      case Some(leg) =>
        actsLegToTrip.put(leg, trip)
      case None =>
    }
  }

  private def indexBeamPlan(): Unit = {
    tours.foreach(tour => tour.trips.foreach(indexTrip))
  }

  def putStrategy(planElement: PlanElement, strategy: Strategy): Unit = {
    val planElementMap = strategies.getOrElseUpdate(planElement, mutable.Map.empty[Class[_ <: Strategy], Strategy])
    planElementMap.put(strategy.getClass, strategy)

    (strategy, planElement) match {
      case (tripModeChoiceStrategy: TripModeChoiceStrategy, tour: Tour) =>
        tripModeChoiceStrategy.tripStrategies(tour, this).foreach { case (trip, _) =>
          putStrategy(trip, tripModeChoiceStrategy)
        }
      case (tripModeChoiceStrategy: TripModeChoiceStrategy, trip: Trip) =>
        putStrategy(trip.activity, tripModeChoiceStrategy) // I don't think this gets used
        trip.leg.foreach(theLeg => putStrategy(theLeg, tripModeChoiceStrategy))
      case (_: TourModeChoiceStrategy, _: Trip) =>
        throw new RuntimeException("Can only set tour mode strategy from within a tour")
      case _ =>
    }
  }

  def getStrategy[T <: Strategy: ClassTag](planElement: PlanElement): T = {
    val forClass: Class[T] = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
    strategies
      .getOrElse(planElement, Map.empty[Class[_ <: Strategy], Strategy])
      .getOrElse(forClass, forClass.getConstructor().newInstance())
      .asInstanceOf[T]
  }

  def getTripStrategy[T <: Strategy: ClassTag](activity: Activity): T = {
    getStrategy(actsLegToTrip(activity))
  }

  def getTourStrategy[T <: Strategy: ClassTag](activity: Activity): T = {
    getStrategy(getTourContaining(activity))
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

  def getTourContaining(index: Int): Tour = {
    getTourContaining(activities(index))
  }

  def getTripContaining(index: Int): Trip = {
    getTripContaining(activities(index))
  }

//  def getTourModeFromTourLegs(tour: Tour): Option[BeamTourMode] = {
//    // TODO: Should this just look at the first/last mode of legs?
//    var tourMode: Option[BeamTourMode] = None
////    if (tour.trips.exists(trip => trip.leg.isDefined)) {
////      tour.trips.foreach(trip =>
////        trip.leg match {
////          case Some(leg) if leg.getMode.equalsIgnoreCase("car") => tourMode = Some(CAR_BASED)
////          case Some(leg) if leg.getMode.equalsIgnoreCase("bike") && !tourMode.contains(CAR_BASED) =>
////            tourMode = Some(BIKE_BASED)
////          case Some(_) => tourMode = Some(WALK_BASED)
////          case _       =>
////        }
////      )
////    }
//    tourMode
//  }

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
