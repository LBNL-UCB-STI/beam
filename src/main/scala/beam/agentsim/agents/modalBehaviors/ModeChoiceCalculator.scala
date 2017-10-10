package beam.agentsim.agents.modalBehaviors

import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.{BeamServices, HasServices}
import beam.agentsim.agents.choice.mode._

import scala.annotation.tailrec
import scala.util.Random

/**
  * BEAM
  */
trait ModeChoiceCalculator extends HasServices with Cloneable{
  def apply(alternatives: Vector[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip]

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
}
