/*
package beam.basicTests;


import static org.junit.Assert.*;

import beamaa.EVGlobalData;
import beam.TestUtilities;
import beam.charging.vehicle.AgentChargingState;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.events.EndChargingSessionEvent;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugStatus;
import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.LinkedList;

public class MultiAgent_AssurePluggedInOrNotBasedOnChargingLevel extends SingleAgentBaseTest{

    @Override
    @Test
    public void test(){
        TestUtilities.setConfigFile("config_7_agents_unplugAfterFastCharging.xml");

        startRun();

        LinkedList<Id<Person>> personIds = new LinkedList<>();

        for (int i = 1; i <= 7; i++) {
            personIds.add(Id.createPersonId(Integer.toString(i)));
        }

        for(Id<Person> personId : personIds){
            for(int i=0; i< evEventCollector.eventCollection.get(0).endChargingSessionEvents.get(personId).size();i++){
                EndChargingSessionEvent event = evEventCollector.eventCollection.get(0).endChargingSessionEvents.get(personId).get(i);
                assureVehicleUnpluggedAfterSlowChargingIfShouldLeave(event.getNominalChargingLevel() < 3, !event.getChargingState().equals(AgentChargingState.POST_CHARGE_PLUGGED), event.shouldDepartAfterChargingSession());
                assureVehicleUnpluggedAfterFastCharging(event.getNominalChargingLevel() >= 3, !event.getChargingState().equals(AgentChargingState.POST_CHARGE_PLUGGED));
                assureVehicleUnpluggedAfterSlowChargingIfQueue(event.getNominalChargingLevel() < 3, !event.getChargingState().equals(AgentChargingState.POST_CHARGE_PLUGGED), event.getNumInChargingQueue() > 0);
                assureVehicleRemainPluggedAfterSlowChargingIfNoQueue(event.getNominalChargingLevel() <3, !event.getChargingState().equals(AgentChargingState.POST_CHARGE_PLUGGED), event.getNumInChargingQueue() == 0);
            }
        }
    }

    // nominal charging level and charging status
    private void assureVehicleUnpluggedAfterFastCharging(boolean isFastCharger, boolean isUnPlugged){
        if(isFastCharger) assertTrue(isUnPlugged);
    }

    private void assureVehicleUnpluggedAfterSlowChargingIfQueue(boolean isSlowCharger, boolean isUnplugged, boolean isQueued){
        if(isSlowCharger && isQueued) assertTrue(isUnplugged);
    }

    private void assureVehicleUnpluggedAfterSlowChargingIfShouldLeave(boolean isSlowCharger, boolean isUnplugged, boolean shouldLeaveAfterCharging){
        if(isSlowCharger && shouldLeaveAfterCharging) assertTrue(isUnplugged);
    }

    private void assureVehicleRemainPluggedAfterSlowChargingIfNoQueue(boolean isSlowCharger, boolean isUnplugged, boolean isQueued){
        if(isSlowCharger && !isQueued) assertTrue(!isUnplugged);
    }
}
*/
