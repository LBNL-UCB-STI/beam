package beam.playground.jdeqsim.akka.parallel.qsim;

import java.util.LinkedList;
import java.util.Random;

public class QFakeModel {

    LinkedList<Long> queue = new LinkedList<>();


    public void moveLinks() {
        Random rand = new Random();

        if (rand.nextDouble() < 0.1) {
            for (long i = 0; i < JavaSingleThreadQsimBenchmark.numberOfElementsToAdd; i++) {
                queue.add(i);
                queue.removeFirst();
            }

        }

    }

}
