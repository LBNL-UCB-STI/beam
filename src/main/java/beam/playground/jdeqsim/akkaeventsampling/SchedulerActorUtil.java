package beam.playground.jdeqsim.akkaeventsampling;

import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.actor.UntypedActor;
import beam.playground.jdeqsim.akkaeventsampling.messages.SchedulerActorJobMessage;
import beam.playground.jdeqsim.akkaeventsampling.messages.SchedulerActorMessageRequest;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

public class SchedulerActorUtil extends UntypedActor {
    public static final String ACTOR_NAME = "SchedulerUtilActor";

    private void createScheduleJob(Long duration, boolean oneTimeJob) {
        ActorRef ref = getContext().actorOf(Props.create(SchedulerActor.class));
        final SchedulerActorMessageRequest schedulerActorRequest =
                new SchedulerActorMessageRequest();
        Cancellable cancellable = null;
        if (oneTimeJob) {
            cancellable = getContext().system().scheduler().scheduleOnce(
                    Duration.create(duration, TimeUnit.MILLISECONDS), ref, schedulerActorRequest,
                    getContext().system().dispatcher(), null);
        }
        cancellable = getContext().system().scheduler().schedule(
                Duration.create(duration, TimeUnit.MILLISECONDS),
                Duration.create(duration, TimeUnit.MILLISECONDS), ref, schedulerActorRequest,
                getContext().system().dispatcher(), null);

    }

    @Override
    public void onReceive(Object message) throws Throwable {
        if (message instanceof SchedulerActorJobMessage) {
            SchedulerActorJobMessage msg = (SchedulerActorJobMessage) message;
            createScheduleJob(msg.getTimeInMilliSec(), msg.isOneTimeJob());


        }
    }
}
