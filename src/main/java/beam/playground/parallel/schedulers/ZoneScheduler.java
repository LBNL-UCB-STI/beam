package beam.playground.parallel.schedulers;

public class ZoneScheduler extends Thread {


    // TODO: create multiple zones (one thread per zone)
    // schedule a random number of events per zone with small probability, that the agent will be handed over to
    // a different zone.
    // a zone has maximum 3 neighbours


    // see, how large the problem has to get, so that the locking overhead is not an issue

    // allow advancement of individual


    // the departure and arrival times should be according to some distribution?


    // handover agents from one zone to other zone!


    // each agent should just schedule new events every 10 seconds


    // play with number of events
    // time difference allowed between events
    // amount of computation needed by each agent

    // socket communication!!!!
    // provide central db of all nodes?


    // simulate local (short tasks) and remote (large tasks/delays)
    // ratio of local to global tasks is 5:1 -> change ratio and see, what happens


    // introduce data structure:
    // do algorithm on local data and lock best resource
    // ask remote for performing algo on its resources (+send info of local reply) -> if remote answer is better than local,
    // reserve on server remote and tell local, otherwise.
    // call needs to be fully asynchronous


    // separate task: find out, which library is best for transferring more and less data on local computer + on AWS.


    @Override
    public void run() {

    }

}
