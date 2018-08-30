package beam.playground.jdeqsimPerformance;

import org.matsim.utils.eventsfilecomparison.EventsFileComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main_EventsFileComparator {

    private static final Logger log = LoggerFactory.getLogger(Main_EventsFileComparator.class);

    public static void main(String[] args) {
        if (args.length != 2) {
            log.error("Expected 2 events files as input arguments but found " + args.length);
            log.info("MainEventsFileComparator eventsFileAbsolutePath1 eventsFileAbsolutePath2");
        } else {
            String filePath = args[0];
            String filePath1 = args[1];

            switch (EventsFileComparator.compare(filePath, filePath1)) {
                case EventsFileComparator.CODE_DIFFERENT_NUMBER_OF_TIMESTEPS:
                    log.info("Different number of time steps");
                    break;
                case EventsFileComparator.CODE_DIFFERENT_TIMESTEPS:
                    log.info("Different time steps");
                    break;
                case EventsFileComparator.CODE_FILES_ARE_EQUAL:
                    log.info("Files are equal");
                    break;
                case EventsFileComparator.CODE_MISSING_EVENT:
                    log.info("Missing Events");
                    break;
                case EventsFileComparator.CODE_WRONG_EVENT_COUNT:
                    log.info("Wrong event count");
                    break;
            }
        }
    }

}
