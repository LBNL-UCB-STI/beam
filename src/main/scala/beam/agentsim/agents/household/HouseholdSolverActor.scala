package beam.agentsim.agents.household

import akka.actor.{ActorLogging, Props}
import akka.pattern.pipe
import beam.utils.logging.LoggingMessageActor
import optimus.optimization._
import optimus.optimization.enums.SolverLib
import optimus.optimization.enums.{PreSolve, SolutionStatus, SolverLib}
import optimus.optimization.model.{INFINITE, MPConstraint, MPFloatVar, MPIntVar}

import scala.concurrent.Future

case object BeginSolving
case object SolutionComplete

object HouseholdSolverActor {

  def props: Props = {
    Props(new HouseholdSolverActor)
  }
}

class HouseholdSolverActor extends LoggingMessageActor with ActorLogging {
  import context._

  override def loggedReceive: Receive = {
    case BeginSolving =>
      //println(self + ": Starting Solving")
      val ongoingSolver: Future[Unit] = Future { solve() }
      ongoingSolver.map(_ => SolutionComplete) pipeTo self
      //ongoingSolver.onComplete(println)
      contextBecome(solving)
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
  def solve(): Unit = {
    implicit val model: MPModel = MPModel(SolverLib.oJSolver)
    val x1 = MPFloatVar("x1", 0, INFINITE)
    val x2 = MPFloatVar("x2", 0, INFINITE)
    val x3 = MPFloatVar("x3", 0, INFINITE)
    maximize(10 * x1 + 6 * x2 + 4 * x3)
    subjectTo((x1 + x2 + x3) <:= 100, (10 * x1 + 4 * x2 + 5 * x3) <:= 600, (2 * x1 + 2 * x2 + 6 * x3) <:= 300)
    start()
    //println(s"$self: objective: $objectiveValue")
    //println(s"$self: x1 = ${x1.value} x2 = ${x2.value} x3 = ${x3.value}")
    release()
  }

  /*
    This is a more simple, but complicated usecase of the solver
    It simply creates a large number of variables and constraints
   */
  def moreComplicatedSolve(): Unit = {
    import context._
    val numberOfVariables = 15360
    var totalTime = 0L
    var totalMb = 0L
    var initialTotalTime = 0L
    var initialTotalMb = 0L
    (1 to 1000).foreach(i => {
      if (i % 10 == 0) println(s"Completing iteration $i")
      val startTime = System.currentTimeMillis
      val mb = 1024 * 1024
      val runtime = Runtime.getRuntime
      /*println("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb)
      println("** Free Memory:  " + runtime.freeMemory / mb)
      println("** Total Memory: " + runtime.totalMemory / mb)
      println("** Max Memory:   " + runtime.maxMemory / mb)
       */
      val usedMemStart = (runtime.totalMemory - runtime.freeMemory) / mb
      implicit val lp: MPModel = MPModel(SolverLib.oJSolver)

      val variableList: Seq[(MPFloatVar, MPConstraint)] = (1 to numberOfVariables).map(x => {
        val variable = MPFloatVar.positive(s"x_$x")
        (variable, add(variable >:= 0))
      })

      val x = MPFloatVar.positive("x")
      maximize(variableList.map(_._1).fold(x + 1)((curr, variable) => curr + variable))
      //FOR MIP - make curr variable be + or - plus random integer in front - constraints need changed?

      start()

      release()
      val end = System.currentTimeMillis
      /*println(s"BEAM took ${end - startTime} milliseconds.")
      println("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb)
      println("** Free Memory:  " + runtime.freeMemory / mb)
      println("** Total Memory: " + runtime.totalMemory / mb)
      println("** Max Memory:   " + runtime.maxMemory / mb)*/
      val usedMemEnd = (runtime.totalMemory - runtime.freeMemory) / mb
      totalMb = totalMb + (usedMemEnd - usedMemStart)
      totalTime = totalTime + (end - startTime)
      if (i == 1) {
        initialTotalMb = totalMb
        initialTotalTime = totalTime
      }
    })
    println(s"Total mb: $totalMb and Total time: $totalTime")
    println(s"Average mb: ${totalMb / 1000} and Average time: ${totalTime / 1000}")
    println(s"Initial Total mb: $initialTotalMb and Initial Total time: $initialTotalTime")
  }
}
