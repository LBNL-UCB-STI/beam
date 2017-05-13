package beam.calibration;

import beam.EVGlobalData;
import beam.events.NestedLogitDecisionEvent;
import beam.events.NestedLogitDecisionEventHandler;
import beam.replanning.EVDailyPlan;
import beam.replanning.module.ExpBetaPlanChargingStrategySelector;
import beam.utils.MathUtil;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.core.controler.events.IterationStartsEvent;
import scala.reflect.internal.util.HasClassPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Calibrate Sitings with utility score
 */
final public class SitingCalibration implements NestedLogitDecisionEventHandler{

    /**
     * Variables
     */
    private static final Logger log = Logger.getLogger(SitingCalibration.class);
    private LinkedHashMap<String, Double> instantMaxExpYesUtilByLink; // Link | Maximum Expected "Yes" Utility in the current iteration
    private LinkedHashMap<String, Double> accumMaxExpYesUtilByLink; // Link | Maximum Expected "Yes" Utility in the current iteration
    private LinkedHashMap<String, Double> prevAccumMaxExpYesUtilByLink; // Link | Expected Maximum "Yes" Utility in the previous iteration
    private LinkedHashMap<String, LinkedHashMap<String, Double>> expMaxYesUtil; // Time | Link | Expected Maximum "Yes" Utility
    private boolean shouldContinueSiting = true;


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

    /**
     * Called at the beginning of each iteration. This evaluates total utility score of the previous simulation with new
     * charging sites. It determines whether we should add additional charging sites.
     * @param event: IterationStartsEvent
     */
    public void run(IterationStartsEvent event){
        log.info("initiating the charging site calibration for iteration: " + event.getIteration());

        // Initialize hashes at every iterations
        initHashes(event.getIteration());

        /*
         * Check total utility score of the previous simulation and determine whether continue the siting
         * TODO: SET YOUR THRESHOLD TO DETERMINE WHETHER CONTINUE OR STOP SITING NEW CHARGERS
         */
        // Get persons from the previous iteration
//        LinkedHashMap<EVDailyPlan, Double> totalWeight = new LinkedHashMap<>();
//        ArrayList<HasPlansAndId> prevPP = ...;
//        for(HasPlansAndId person:prevPP){
//        LinkedHashMap<EVDailyPlan, Double> weight = new ExpBetaPlanChargingStrategySelector().calcWeights(person);
//        totalWeight += weight;
//        }

        // Get the improvement rate

        // Determine whether continue or stop siting new chargers
        shouldContinueSiting = true;


        /*
         * Site new chargers at where maximum utilities are high
         */
        if(event.getIteration()!=0 && shouldContinueSiting){
            // TODO: DETERMINE HOW MANY NEW CHARGERS TO INSTALL
            // This depends on the threshold


            // TODO: DETERMINE CHARGER SITES BASED ON THE MAXIMUM EXPECTED UTILITY
            // USE prevAccumMaxExpYesUtilByLink.


            // TODO: INSTALL NEW CHARGERS HERE
        }

        // Set scheduler to call a method to track
        EVGlobalData.data.scheduler.addCallBackMethod(0.0,this ,"updateMaxExpUtilByLink", 0.0, this);
    }

    /**
     * Event listener for NestedLogitDecisionEvent. This tracks temporal maximum utilities of each link
     * @param event: event object created when a nested logit decision is made
     */
    @Override
    public void handleEvent(NestedLogitDecisionEvent event) {
        // Link id
        String linkId = event.getAttributes().get(NestedLogitDecisionEvent.ATTRIBUTE_LINK);

        // Maximum expected utility
        // TODO: choice utility must be fixed to store maximum expected utility of "yesCharge"
        String maxUtil = event.getAttributes().get(NestedLogitDecisionEvent.ATTRIBUTE_CHOICE_UTILITY);

        // Update maximum expected utility in the instant hash of this time slot -- this is reinitialized at every time slot
        if(instantMaxExpYesUtilByLink.containsKey(linkId)) instantMaxExpYesUtilByLink.put(linkId, instantMaxExpYesUtilByLink.get(linkId) + Double.valueOf(maxUtil));
        else instantMaxExpYesUtilByLink.put(linkId, Double.valueOf(maxUtil));

        // Update maximum expected utility in the accumulated utility hash -- this is not reinitialized during each iteration
        if(accumMaxExpYesUtilByLink.containsKey(linkId)) accumMaxExpYesUtilByLink.put(linkId, accumMaxExpYesUtilByLink.get(linkId) + Double.valueOf(maxUtil));
        else accumMaxExpYesUtilByLink.put(linkId, Double.valueOf(maxUtil));
    }

    /**
     * Update Maximum expected Utility by link at the current time slot
     */
    private void updateMaxExpUtilByLink(){
        // Update
        String timeNow = String.valueOf(EVGlobalData.data.now/3600.0);
        expMaxYesUtil.put(timeNow, instantMaxExpYesUtilByLink);

        // Reinitialize instant utility hash
        instantMaxExpYesUtilByLink = new LinkedHashMap<>();

        // Set scheduler
        Double writeInterval = 15.0 * 60.0; // 15 minutes
        EVGlobalData.data.scheduler.addCallBackMethod(MathUtil.roundUpToNearestInterval(EVGlobalData.data.now + writeInterval, writeInterval),
                this ,"updateMaxExpUtilByLink", 0.0, this);
    }

    /**
     * Called at the beginning of every iteration
     * @param iteration: current iteration number
     */
    @Override
    public void reset(int iteration) {}

    /**
     * Initialize hashMaps that store maximum expected utility by links
     * @param iteration: current iteration number
     */
    private void initHashes(int iteration){
        // Initialize params
        instantMaxExpYesUtilByLink = new LinkedHashMap<>(); // not necessary, but for design purpose
        accumMaxExpYesUtilByLink = new LinkedHashMap<>();

        // Update previous accumulate utilities
        if(iteration==0) prevAccumMaxExpYesUtilByLink = new LinkedHashMap<>();
        else prevAccumMaxExpYesUtilByLink = accumMaxExpYesUtilByLink;
    }
}
