package beam.metasim.playground.colin

import akka.actor.{ActorSystem,Inbox}
import akka.actor.Props
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef

class BeamAkkaSystem {
    var system : ActorSystem = null
    var myAgent : ActorRef = null
    var mySchedule : ActorRef = null
    var inbox : Inbox = null

    def init() {
      this.system = ActorSystem("ToySystem")
      this.myAgent = this.system.actorOf(Props[BeamAgent])
      this.mySchedule = this.system.actorOf(Props[Scheduler])
    
      // Create an "actor-in-a-box"
      this.inbox = Inbox.create(system)
      this.inbox.send(mySchedule, new BeamEvent(myAgent, 1.0, Transition, 1))
      this.inbox.send(mySchedule, new BeamEvent(myAgent, 5.0, Transition, 1))
      this.inbox.send(mySchedule, new BeamEvent(myAgent, 2.0, Transition, 1))
      this.inbox.send(mySchedule, new BeamEvent(myAgent, 4.0, Transition, 1))
    }
    
    def start() {
      this.inbox.send(mySchedule, "start")
    }
//    system.scheduler.scheduleOnce(0.milliseconds,myAgent,"bad message")

//  // Wait 5 seconds for the reply with the 'greeting' message
//  val Greeting(message1) = inbox.receive(5.seconds)
//  println(s"Greeting: $message1")
//
//  // Change the greeting and ask for it again
//  greeter.tell(WhoToGreet("typesafe"), ActorRef.noSender)
//  inbox.send(greeter, Greet)
//  val Greeting(message2) = inbox.receive(5.seconds)
//  println(s"Greeting: $message2")
//
//  val greetPrinter = system.actorOf(Props[GreetPrinter])
//  // after zero seconds, send a Greet message every second to the greeter with a sender of the greetPrinter
//  system.scheduler.schedule(0.seconds, 1.second, greeter, Greet)(system.dispatcher, greetPrinter)
  

}