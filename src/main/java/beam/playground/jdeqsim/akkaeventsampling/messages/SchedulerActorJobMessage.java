package beam.playground.jdeqsim.akkaeventsampling.messages;

import java.io.Serializable;


public class SchedulerActorJobMessage implements IRequest, Serializable {
    private long timeInMilliSec = 5;
    private boolean oneTimeJob = false;

    public SchedulerActorJobMessage(long timeInMilliSec) {
        this.timeInMilliSec = timeInMilliSec;
    }

    public SchedulerActorJobMessage(long timeInMilliSec, boolean oneTimeJob) {
        this.timeInMilliSec = timeInMilliSec;
        this.oneTimeJob = oneTimeJob;
    }

    public long getTimeInMilliSec() {
        return timeInMilliSec;
    }

    public boolean isOneTimeJob() {
        return oneTimeJob;
    }
}
