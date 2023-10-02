from time import time
from components import ChaDepParent
import numpy as np

from components.SimBroker import SimBroker
from components.Vehicle import Vehicle
import components
import logging

class ChaDepLimCon(ChaDepParent):

    def step(self, timestep):
        # class method to perform control action for the next simulation step.
        '''repark vehicles based on their charging desire with the parent method'''
        self.repark()
        logging.info("Vehicles reparked in %s" % self.ChargingStationId)

        ''' control action'''

        '''# calculate maximum available power for charging, for 3 different cases'''
        if self.BtmsSoc() <= self.BtmsMinSoc:
            P_max = self.GridPowerUpper
        # if Btms energy content is large enough to power for the next timestep full discharge rating
        elif self.BtmsEn - timestep/3.6e3 * self.BtmsMaxPower >= self.BtmsSize * self.BtmsMinSoc:
            P_max = self.GridPowerUpper + self.BtmsMaxPower
        # this is the intermediate case and the chargingPower to reach the minimum SOC
        else:
            P_max = self.GridPowerUpper + (self.BtmsEn - self.BtmsSize * self.BtmsMinSoc) / (timestep/3.6e3)
        logging.info("maximum power available for charging: %s" % P_max)

        '''# now assign the charging powers to each vehicle, prioritized by their charging desire'''
        self.P_ChargeDelivered = self.distributeChargingPowerToVehicles(timestep, P_max)
        logging.debug("vehicle states updated for charging station {}".format(self.ChargingStationId))

        '''# now find out how to charge or discharge BTMS''' 
        sumPowers = sum(self.ChBaPower)
        # if sum of charging power is greater than grid power limit, the btms must be discharged
        if sumPowers >= self.GridPowerUpper:
            self.P_BTMS = self.GridPowerUpper - sumPowers # result is negative
        # if that is not the case, we might be able to charge for one timestep, if SOC < Max SOC
        elif self.BtmsEn < self.BtmsSize * self.BtmsMaxSoc:
            self.P_BTMS = min([self.getBtmsMaxPower(timestep), self.GridPowerUpper - sumPowers])
        # if that doesn't work, it seems like Btms is full, then charging power is 0.
        else:
            self.P_BTMS = 0
        
        '''calcualte dispatchable BTMS power'''
        self.P_BTMS = self.BtmsGetPowerDeliverable(timestep, self.P_BTMS)

        '''calculate grid power'''
        self.P_Grid = self.P_ChargeDelivered + self.P_BTMS
        logging.info('P_Grid: {:.2f}'.format(self.P_Grid))

        '''Write chargingStation states for k in ResultWriter'''
        self.ResultWriter.updateChargingStationState(self.SimBroker.t_act, self)
        logging.debug("results written for charging station {}".format(self.ChargingStationId))

        '''# update BTMS state for k+1'''
        # BTMS
        self.BtmsAddPower(self.P_BTMS, timestep)
        logging.debug("BTMS state updated for charging station {}".format(self.ChargingStationId))

        '''write vehicle states for k in ResultWriter and update vehicle states for k+1'''
        # Vehicles
        self.updateVehicleStatesAndWriteStates(self.ChBaPower, timestep)
        logging.debug("vehicle states updated for charging station {}".format(self.ChargingStationId))

        '''determine power desire for next time step'''
        PowerDesire = 0
        for i in range(0,len(self.ChBaVehicles)):
            if isinstance(self.ChBaVehicles[i], components.Vehicle):
                PowerDesire += min([self.ChBaVehicles[i].getMaxChargingPower(timestep), self.ChBaMaxPower[i]])
        self.PowerDesire = PowerDesire
        self.BtmsPowerDesire = self.getBtmsMaxPower(timestep)
        logging.debug("power desires updated for charging station {}".format(self.ChargingStationId))

        '''release vehicles when full and create control outputs'''
        self.resetOutput()
        r1 = self.chBaReleaseThresholdAndOutput()
        r2 = self.queueReleaseThresholdAndOutput()
        logging.debug("vehicles released for charging station {}".format(self.ChargingStationId))
        released_Vehicles = r1 + r2

        # write vehicle states before releasing them (to have final SOC)
        for x in r1:
            possiblePower = x.getMaxChargingPower(timestep)
            self.ResultWriter.updateVehicleStates(
                    t_act=self.SimBroker.t_act + timestep, vehicle=x, ChargingStationId=self.ChargingStationId, QueueOrBay=False, ChargingPower=0, possiblePower=possiblePower)
        for x in r2:
            possiblePower = x.getMaxChargingPower(timestep)
            self.ResultWriter.updateVehicleStates(
                    t_act=self.SimBroker.t_act + timestep, vehicle=x, ChargingStationId=self.ChargingStationId, QueueOrBay=True, ChargingPower=0, possiblePower=possiblePower)

        # add release events
        for x in released_Vehicles:
            self.ResultWriter.releaseEvent(self.SimBroker.t_act, x, self.ChargingStationId)
        logging.debug("vehicle release events written for charging station {}".format(self.ChargingStationId))

        '''checks'''
        if len(self.ChBaVehicles)!=self.ChBaNum:
            raise ValueError("Size of ChargingBay List shouldn't change")
        