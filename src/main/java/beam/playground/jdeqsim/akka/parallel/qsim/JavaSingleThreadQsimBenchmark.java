package beam.playground.jdeqsim.akka.parallel.qsim;

import java.util.LinkedList;

public class JavaSingleThreadQsimBenchmark {

    public static int numberOfFakeLinks = 400000;
    public static int numberOfTimeSteps = 1;
    public static long numberOfElementsToAdd = 1L;
    public static double shareOfActiveLinks = 0.5;
    public static int messageSize = 10000;


    public static void main(String[] args) {

        double time = System.currentTimeMillis();
        LinkedList<QFakeModel> qmodel = new LinkedList();
        for (int i = 0; i < numberOfFakeLinks; i++) {
            qmodel.add(new QFakeModel());
        }

        for (int i = 0; i < numberOfTimeSteps; i++) {
            for (QFakeModel qfakeModel : qmodel) {
                qfakeModel.moveLinks();
            }
        }


        System.out.println(Math.round(((System.currentTimeMillis() - time) / 1000)) + " [s]");
    }

}
