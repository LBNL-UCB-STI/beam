package scripts.protocolvis

import scripts.protocolvis.MessageReader.Actor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
  * @author Dmitry Openkov
  */
class SequenceDiagramTest extends AnyWordSpec with Matchers {
  "userFriendlyActorName" when {
    "provided with a PersonAgent actor" should {
      "return its id" in {
        SequenceDiagram.userFriendlyActorName(Actor(parent = "population/1", name = "3")) shouldBe "Person"
      }
    }
  }
  "parseTriggerWithId" when {
    "Internal trigger contains additional info" should {
      "return trigger with tick and id" in {
        val triggerWithId = "TriggerWithId(StartLegTrigger(21600,BeamLeg(CAR @ 21600,dur:145,path: 143 .. 360)),3266)"
        SequenceDiagram.parseTriggerWithId(triggerWithId) should be(
          "StartLegTrigger(tick=21600) id=3266"
        )
      }
    }
  }
  "parseScheduleTrigger" when {
    "provided with a ScheduleTrigger" should {
      "return trigger and actor" in {
        val scheduleTrigger = "ScheduleTrigger(ChargingTimeOutTrigger(38349,3 (PHEV,-5.185524856616993km))," +
          "Actor[akka://ClusterSystem/user/BeamMobsim.iteration/ChargingNetworkManager#-915589974],0)"
        SequenceDiagram.parseScheduleTrigger(scheduleTrigger) should be(
          "ChargingTimeOutTrigger(tick=38349) for ChargingNetworkManager"
        )
      }
    }
  }
  "parseAkkaActor" when {
    "provided with an Actor string" should {
      "return Actor class" in {
        val actor = "Actor[akka://ClusterSystem/user/BeamMobsim.iteration/ChargingNetworkManager#-915589974]"
        SequenceDiagram.parseAkkaActor(actor) shouldBe Actor("BeamMobsim.iteration", "ChargingNetworkManager")
      }
    }
  }
  "parseCompletionNotice" when {
    "CompletionNotice contains multiple new triggers" should {
      "return them all" in {
        val completionNotice = "CompletionNotice(3274,Vector(ScheduleTrigger(" +
          "StartLegTrigger(22050,BeamLeg(CAR @ 22050,dur:70,path: 229 .. 228))," +
          "Actor[akka://ClusterSystem/user/BeamMobsim.iteration/RideHailManager/rideHailAgent-48#-443651850],0)," +
          " ScheduleTrigger(RideHailRepositioningTrigger(22350)," +
          "Actor[akka://ClusterSystem/user/BeamMobsim.iteration/RideHailManager#625447039],0)))"
        SequenceDiagram.parseCompletionNotice(
          completionNotice
        ) shouldBe "Completion of 3274, StartLegTrigger(tick=22050) for RideHailAgent, RideHailRepositioningTrigger(tick=22350) for RideHailManager"
      }
      "CompletionNotice contains no new triggers" should {
        "return only 'Completion of <tick>'" in {
          val completionNotice = "CompletionNotice(322,Vector())"
          SequenceDiagram.parseCompletionNotice(completionNotice) shouldBe "Completion of 322"
        }
      }
      "CompletionNotice contains a list of triggers" should {
        "return all of them'" in {
          val completionNotice = "CompletionNotice(3274,List(ScheduleTrigger(" +
            "StartLegTrigger(22050,BeamLeg(CAR @ 22050,dur:70,path: 229 .. 228))," +
            "Actor[akka://ClusterSystem/user/BeamMobsim.iteration/RideHailManager/rideHailAgent-48#-443651850],0)))"
          SequenceDiagram.parseCompletionNotice(
            completionNotice
          ) shouldBe "Completion of 3274, StartLegTrigger(tick=22050) for RideHailAgent"
        }
      }
    }
  }
}
