package beam.sim;

import beam.EVGlobalData;
import beam.sim.traveltime.BeamRouterR5;
import org.matsim.core.mobsim.framework.Mobsim;
import org.apache.log4j.Logger;

/**
 * BEAM
 */
public class BeamMobsim implements Mobsim {
    private static final Logger log = Logger.getLogger(BeamMobsim.class);

    @Override
    public void run() {
        log.info("Running BEAM Mobsim");

        for(Double time = 0.0; time <= EVGlobalData.data.config.qsim().getEndTime(); time += 1.0){
            if(Math.round(time) % 3600 == 0){
                log.info("BeamMobsim at simulation time: "+Math.round(time/3600.0)+" hours "+(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/(Math.pow(1000,3))+"(GB)");
            }
            doSimStep(time);
        }
    }
    public void doSimStep(double now) {
        EVGlobalData.data.now = now;
        EVGlobalData.data.scheduler.doSimStep(now);
    }
}
