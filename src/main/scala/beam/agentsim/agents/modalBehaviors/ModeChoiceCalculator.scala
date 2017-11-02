package beam.agentsim.agents.modalBehaviors

import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.{BeamServices, HasServices}
import beam.agentsim.agents.choice.mode._
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual

import scala.annotation.tailrec
import scala.util.Random

/**
  * BEAM
  */
trait ModeChoiceCalculator extends HasServices with Cloneable{
  def apply(alternatives: Vector[EmbodiedBeamTrip],extraAttributes: Option[AttributesOfIndividual]): Option[EmbodiedBeamTrip]
  def apply(alternatives: Vector[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    this(alternatives,None)
  }

  @tailrec
  final def chooseRandomAlternativeIndex(alternatives:Vector[EmbodiedBeamTrip]): Option[Int] ={
    if(alternatives.nonEmpty){
          val tmp = Random.nextInt(alternatives.size)
          if(alternatives(tmp).legs.nonEmpty){
            Some(tmp)
          }else{
            chooseRandomAlternativeIndex((alternatives.toSet - alternatives(tmp)).toVector)
          }
    } else{
      None
    }
  }

  override def clone(): ModeChoiceCalculator = {
    this.clone()
  }
}

object ModeChoiceCalculator {
  def apply(classname: String, beamServices: BeamServices): ModeChoiceCalculator = {
    classname match {
      case "ModeChoiceLCCM" =>
        ModeChoiceLCCM(beamServices)
      case "ModeChoiceTransitOnly" =>
        new ModeChoiceTransitOnly(beamServices)
      case "ModeChoiceDriveOnly" =>
        new ModeChoiceDriveOnly(beamServices)
      case "ModeChoiceRideHailOnly" =>
        new ModeChoiceRideHailOnly(beamServices)
      case "ModeChoiceTransitIfAvailable" =>
        new ModeChoiceTransitIfAvailable(beamServices)
      case "ModeChoiceDriveIfAvailable" =>
        new ModeChoiceDriveIfAvailable(beamServices)
      case "ModeChoiceRideHailIfAvailable" =>
        new ModeChoiceRideHailIfAvailable(beamServices)
      case "ModeChoiceUniformRandom" =>
        new ModeChoiceUniformRandom(beamServices)
      case "ModeChoiceMultinomialLogit" =>
        ModeChoiceMultinomialLogit(beamServices)
    }
  }
  case class AttributesOfIndividual(householdIncome: Double, householdSize: Int, isMale: Boolean, numCars: Int, numBikes: Int)
}

