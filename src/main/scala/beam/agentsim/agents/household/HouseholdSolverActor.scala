package beam.agentsim.agents.household

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.pipe
import optimus.optimization._
import optimus.optimization.enums.SolverLib
import optimus.optimization.model.{INFINITE, MPFloatVar}

import scala.concurrent.Future

case object BeginSolving
case object SolutionComplete

object HouseholdSolverActor {
  def props: Props = {
    Props(new HouseholdSolverActor)
  }
}

class HouseholdSolverActor extends Actor with ActorLogging{
  import context._
  implicit val model = MPModel(SolverLib.oJSolver)

  var ongoingSolver: Future[Unit] = _
  override def receive: Receive = {
    case BeginSolving =>
      //println(self + ": Starting Solving")
      ongoingSolver = Future { solve }
      ongoingSolver.map(_ =>  SolutionComplete) pipeTo self
      //ongoingSolver.onComplete(println)
      context become solving
    case _ =>
  }

  def solving: Receive = {
    case SolutionComplete =>
      //println(self + ": This is where you can tell the parent what you learned")
      context stop self
    case _ =>
  }

  /*
    This is where the heavy lifting is done by the solver
    This is just a simple equation, and should be changed to handle whichever you feel is needed
   */
  def solve: Unit = {
    val x1 = MPFloatVar("x1", 0, INFINITE)
    val x2 = MPFloatVar("x2", 0, INFINITE)
    val x3 = MPFloatVar("x3", 0, INFINITE)
    maximize(10 * x1 + 6 * x2 + 4 * x3)
    subjectTo(
      (x1 + x2 + x3) <:= 100,
      (10 * x1 + 4 * x2 + 5 * x3) <:= 600,
      (2 * x1 + 2 * x2 + 6 * x3) <:= 300)
    start()
    //println(s"$self: objective: $objectiveValue")
    //println(s"$self: x1 = ${x1.value} x2 = ${x2.value} x3 = ${x3.value}")
    release()
  }
}