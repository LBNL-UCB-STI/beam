package beam.playground.jdeqsim.akka.parallel.qsim;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;

import java.math.BigInteger;
import java.util.LinkedList;
import java.util.Random;

public class CentralClockActor extends UntypedActor {

    LinkedList<ActorRef> qfakeModels;
    int linkMessagesReceived = 0;
    int currentTimeStep = 0;
    double startTime;

    public CentralClockActor(LinkedList<ActorRef> qfakeModels) {
        this.qfakeModels = qfakeModels;
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof String) {
            String s = (String) msg;
            if (s.equalsIgnoreCase("start")) {
                startTime = System.currentTimeMillis();
                startNewRound();
            } else if (s.equalsIgnoreCase("linkMessage")) {
                linkMessagesReceived++;

                if (linkMessagesReceived == qfakeModels.size()) {
                    linkMessagesReceived = 0;

                    startNewRound();
                    currentTimeStep++;
                }

                if (currentTimeStep > JavaSingleThreadQsimBenchmark.numberOfTimeSteps) {
                    System.out.println(Math.round(((System.currentTimeMillis() - startTime) / 1000)) + " [s]");
                    System.exit(0);
                }

            }
        }
    }

    private void startNewRound() {
        Random rand = new Random();
        for (ActorRef fakeModel : qfakeModels) {
            fakeModel.tell(new BigInteger(JavaSingleThreadQsimBenchmark.messageSize, rand).toString(32), getSelf());
        }
    }

}
