package beam.physsim.jdeqsim;

import beam.utils.DebugLib;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.handler.BasicEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JDEQSimMemoryFootprint implements BasicEventHandler {

    private static final Logger log = LoggerFactory.getLogger(JDEQSimMemoryFootprint.class);
    private final boolean debugMode;
    private int prevHour = 0;

    public JDEQSimMemoryFootprint(boolean debugMode) {
        this.debugMode = debugMode;
    }

    @Override
    public void handleEvent(Event event) {
        int currentHour = (int) Math.floor(event.getTime() / 3600.0);
        if (Math.abs(prevHour - currentHour) >= 1) {
            if (debugMode) {
                log.info(DebugLib.getMemoryLogMessage("Hour " + currentHour + " completed. "));
            } else {
                log.info("Hour " + currentHour + " completed. ");
            }
            prevHour = currentHour;
        }
    }
}
