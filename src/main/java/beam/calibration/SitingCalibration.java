package beam.calibration;

import org.apache.log4j.Logger;
import org.matsim.core.controler.events.IterationStartsEvent;

/**
 * Calibrate Sitings with utility score
 */
final public class SitingCalibration {

    /**
     * Variables
     */
    private static final Logger log = Logger.getLogger(SitingCalibration.class);

    /**
     * Make the class singleton
     */
    private static SitingCalibration instance = null;
    private SitingCalibration() {
        // Defeat instantiation.
    }
    public static SitingCalibration getInstance() {
        if(instance == null) {
            instance = new SitingCalibration();
        }
        return instance;
    }
    public void run(IterationStartsEvent event){
        log.info("initiating the charging site calibration for iteration: " + event.getIteration());

        // TODO: CHECK OVERALL UTILITY SCORE OF THE PREVIOUS SIMULATION
        // SET YOUR THRESHOLD TO STOP SITING NEW CHARGERS

        // TODO: TRACK EXPECTED MAXIMUM UTILITY
        //

        // TODO: SITE NEW CHARGERS AT WHERE EXPECTED MAXIMUM UTILITIES ARE HIGH

    }
}
