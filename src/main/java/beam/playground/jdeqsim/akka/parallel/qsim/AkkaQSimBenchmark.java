package beam.playground.jdeqsim.akka.parallel.qsim;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

import java.util.LinkedList;

public class AkkaQSimBenchmark {

    public static void main(String[] args) {

        ActorSystem system = ActorSystem.create("PhysicalSimulation-Benchmark");
        LinkedList<ActorRef> qfakeModels = new LinkedList();

        for (int i = 0; i < JavaSingleThreadQsimBenchmark.numberOfFakeLinks; i++) {
            qfakeModels.add(system.actorOf(Props.create(QFakeModelActor.class)));
        }
        ActorRef centralClockActor = system.actorOf(Props.create(CentralClockActor.class, qfakeModels));

        centralClockActor.tell("start", ActorRef.noSender());

    }

}
