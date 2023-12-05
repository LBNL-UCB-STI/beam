#from msilib.schema import Error
from components import Vehicle
from components import ResultWriter
from components import SimBroker
import components
import numpy as np

class ChaDepParent:
    # TODO get rid of SimBroker and exchange with actual time t_act
    def __init__(self, ChargingStationId, ResultWriter: ResultWriter, SimBroker: SimBroker, ChBaMaxPower, ChBaParkingZoneId, ChBaNum: int, BtmsSize = 100, BtmsC = 1, BtmsMaxSoc = 1.0, BtmsMinSOC = 0.0, BtmsSoc0 = 0.50, calcBtmsGridProp = False, GridPowerMax_Nom = 1 , GridPowerLower = -1, GridPowerUpper = 1):

        '''ChargingStationIdentity'''
        self.ChargingStationId  = ChargingStationId

        '''Result Writer''' # reference to result writer object
        self.ResultWriter       = ResultWriter

        '''BTMS''' 
        # properties
        if calcBtmsGridProp:
            self.BtmsSize       = sum(ChBaMaxPower)/2 # empirical formula (check with literature), used for initializing
        else:
            self.BtmsSize       = BtmsSize          # size of the BTMS in kWh
        self.BtmsC              = BtmsC             # C-Rating of BTMS (1C is a complete charge an hour) C= [1/h]
        self.BtmsMaxPower       = BtmsC * self.BtmsSize
        self.BtmsMaxSoc         = BtmsMaxSoc        # maximal allowed SOC of BTMS
        self.BtmsMinSoc         = BtmsMinSOC        # minimal allowed SOC of BTMS
        #variables:
        self.BtmsEn             = BtmsSoc0 * self.BtmsSize # BTMS energy content at initialization [kWh]
        self.P_BTMS             = 0                 # actual charging power of the btms
        self.BtmsEfficiency     = 0.95              # efficiency per charge/decharge
        '''Charging Bays'''
        #properties
        self.ChBaNum            = ChBaNum           # number of charging bays determined by ChBaMaxPower vector
        self.ChBaMaxPower       = ChBaMaxPower      # list of maximum power for each charging bay in kW
        self.ChBaMaxPower_abs   = max(ChBaMaxPower) # maximum value from list above
        self.ChBaParkingZoneId  = ChBaParkingZoneId # list of parking zone ids associated with max power list; can be longer than ChBaMaxPower or ChBaNu for testing with reduced number of charging bays
        #variables
        self.ChBaVehicles       = self.chBaInit(self.ChBaNum) # list for Vehicles objects, which are in charging bays. False, if no vehicle
        if len(ChBaMaxPower) != ChBaNum:
            print(len(ChBaMaxPower))
            print(ChBaNum)
            raise ValueError(' size of list with maximal plug power doesnt equals number of charging bays')
        # variables
        self.ChBaPower          = []                # this is the variable for which charging power for each bay is assigned.
        
        '''Grid'''
        self.P_Grid                 = 0.0                       # actual power of the grid   [kW]
        '''Grid Constraints'''
        if calcBtmsGridProp:
            self.GridPowerMax_Nom   = 0.35*sum(ChBaMaxPower)    # empirical formula (check with literature)
        else:
            self.GridPowerMax_Nom   = GridPowerMax_Nom          # maximum power withdrawal from grid, nominal value (can be time-varying)
        self.GridPowerLower         = 0                         # will be assigned for each step through DERMS
        self.GridPowerUpper         = self.GridPowerMax_Nom     # will be assigned for each step through DERMS

        '''Power Desire'''
        #variables
        self.PowerDesire            = 0               # Power Desire to DERMS
        self.BtmsPowerDesire        = 0

        '''Queue of Vehicles'''
        #variables
        self.Queue                  = []                # list for Vehicles objects, which are in the queue.

        '''Simulation Data'''
        self.SimBroker              = SimBroker         # reference to SimBroker object

        '''Rating Metric'''
        #variables
        self.EnergyLagSum           = 0                 # sum of energy lags as rating metric
        self.TimeLagSum             = 0                 # sum of time lags of vehicles as rating metric

        ''' Control Output Results'''
        # control output should be saved to these variables after each step
        self.output_vehicles           = []                # list of vehicle ids
        self.output_power              = []                # list of associated charging power commands
        self.output_release            = []                # list of Booleans for Release
    
    def plotPrediction(directory):
        pass

    def BtmsSoc(self):
        return self.BtmsEn/self.BtmsSize
    
    def getBtmsMaxPower(self, timestep):
        # TODO: implement efficiency
        x = min([self.BtmsMaxPower, (self.BtmsSize * self.BtmsMaxSoc - self.BtmsEn) / (timestep/3.6e3)])
        if x<0:
            x=0
        return x

    def BtmsGetPowerDeliverable(self, power, timestep) -> float:
        # power in kW and timestep in s
        # charging loss implemented
        BtmsEn_Last = self.BtmsEn
        if power >= 0:
            BtmsEn_New = BtmsEn_Last + self.BtmsEfficiency * power * timestep/3.6e3
            if BtmsEn_New > self.BtmsSize: # make sure that the energy content is not larger than the capacity
                BtmsEn_New = self.BtmsSize
                power_return = (BtmsEn_New - BtmsEn_Last) / (timestep/3.6e3) / self.BtmsEfficiency
            else:
                power_return = power
        else: # if power < 0 --> power is negative, so substraction
            BtmsEn_New = BtmsEn_Last + 1/self.BtmsEfficiency * power * timestep/3.6e3
            if BtmsEn_New < 0: # make sure that the energy content is not smaller than 0
                BtmsEn_New = 0
                power_return = (BtmsEn_New - BtmsEn_Last) / (timestep/3.6e3) * self.BtmsEfficiency # calculate actually discharged/charged power and return it
            else:
                power_return = power
        return power_return

    def BtmsAddPower(self, power, timestep):
        if power >= 0:
            self.BtmsEn += self.BtmsEfficiency * power * timestep/3.6e3
            if self.BtmsEn > self.BtmsSize + 0.01:
                self.BtmsEn = self.BtmsSize
                raise ValueError('Btms energy content is larger than capacity')
        else: # if power < 0 --> power is negative, so substraction
            self.BtmsEn += 1/self.BtmsEfficiency * power * timestep/3.6e3
            if self.BtmsEn < - 0.01:
                self.BtmsEn = 0
                raise ValueError('Btms energy content is smaller than 0')
    

    def chBaInit(self, ChBaNum):
        # initialize the list of ChargingBay Vehicles with False for no vehicles parked
        ChBaVehicles = []
        for i in range(0, ChBaNum):
            ChBaVehicles.append(False)
        return ChBaVehicles

    def chBaActiveCharges(self):
        num_Charges = 0
        for x in self.ChBaVehicles:
            if not x == False:
                num_Charges +=1
        return num_Charges
    
    def chBaAdd(self, vehicle):
        # add a vehicle to the charging Bay
        did_add = False
        for i in range(0, len(self.ChBaVehicles)):
            if self.ChBaVehicles[i] == False:
                self.ChBaVehicles[i] = vehicle
                did_add = True
                j = i
                break # can leave after adding
        if did_add == False:
            raise ValueError('The Vehicle couldnt be added')
        #returns the positions  where vehicle was added.
        return j

    def resetOutput(self):
        # This is to provide an output to beam for every vehicle
        self.output_vehicles   = []
        self.output_power      = []
        self.output_release    = []

    def chBaReleaseThresholdAndOutput(self, threshold = 0.9999):
        # threshold gives a threshold of desired energy, after which vehicle can be released.
        # this function also creates the control outputs
        out = []
        for i in range(0, len(self.ChBaVehicles)):
            # find sufficient charged vehicle, put them in the out array and replace them with false
            if isinstance(self.ChBaVehicles[i], components.Vehicle):
                self.output_vehicles.append(self.ChBaVehicles[i].VehicleId) # for control output
                self.output_power.append(self.ChBaPower[i]) # for control ouput
                if self.ChBaVehicles[i].VehicleEngy > threshold * self.ChBaVehicles[i].VehicleDesEngy:
                    out.append(self.ChBaVehicles[i])
                    self.ChBaVehicles[i] = False
                    self.output_release.append(True) # for control output
                else:
                    self.output_release.append(False) # for control output
        return out
    
    def queueReleaseThresholdAndOutput(self, threshold = 0.9999):
        # threshold gives a threshold of desired energy, after which vehicle can be released.
        # this function also creates the control outputs
        pop = []
        out = []
        for i in range(0, len(self.Queue)):
            # find out which indices need to be popped out
            if isinstance(self.Queue[i], components.Vehicle):
                self.output_vehicles.append(self.Queue[i].VehicleId) # for control output
                self.output_power.append(0) # for control output
                if self.Queue[i].VehicleEngy > threshold * self.Queue[i].VehicleDesEngy:
                    pop.append(i)
                    self.output_release.append(True)
                else:
                    self.output_release.append(False)
        # pop this indices out
        for i in range(0, len(pop)):
            out.append(self.Queue.pop(pop[i]-i)) # to make up the loss of popped out elements before
        return out
    
    def getControlOutput(self):
        return self.output_vehicles, self.output_power, self.output_release

    def planning(self):
        # class method to perform day planning
        pass

    def arrival(self, vehicle: Vehicle, t_act):
        # class method to let vehicles arrive
        # calculate charging desire
        vehicle.ChargingDesire = self.chargingDesire(vehicle)
        vehicle.updateEnergyLag(t_act)
        if vehicle.VehicleEngy < vehicle.VehicleDesEngy:
            # if vehicle is not fully charged, add to queue
            self.Queue.append(vehicle)
            self.ResultWriter.arrivalEvent(t_act, vehicle, self.ChargingStationId)
        else:
            # if vehicle is fully charged, don't add it, but throw an arrival and a release event
            self.ResultWriter.arrivalEvent(t_act, vehicle, self.ChargingStationId)
            self.ResultWriter.releaseEvent(t_act, vehicle, self.ChargingStationId)
        
    def departure(self, vehicleIds, t_act):
        # TODO: Did I use this?
        # vehicleIds is a list of vehicleIds which should be released
        # for vehicles in charging bay
        for i in range(0, len(self.ChBaVehicles)):
            if self.ChBaVehicles[i] != False: # if a vehicle is in bay
                if np.isin(self.ChBaVehicles[i].VehicleId, vehicleIds).any(): # if the vehicleId of this vehicle was not released before
                    self.ResultWriter.forcedReleaseEvent(t_act, self.ChBaVehicles[i], self.ChargingStationId)
                    self.ChBaVehicles[i] = False # release Vehicle
        # for vehicles in Queue
        out = []
        for i in range(0, len(self.Queue)):
            if np.isin(self.Queue[i].VehicleId, vehicleIds).any(): # if the vehicleId of this vehicle was not released before
                out.append(i)
                self.ResultWriter.forcedReleaseEvent(t_act, self.Queue[i], self.ChargingStationId)
        # pop this vehicles out
        for i in range(0, len(out)):
            self.Queue.pop(out[i]-i)

    def repark(self):
        # class method to repark the vehicles, based on their charging desire
        # TODO: This doesn't take into account different plug powers
        # add vehicles to charging bays if possible
        while self.chBaActiveCharges() < self.ChBaNum and len(self.Queue) > 0:
            add = self.Queue.pop(0)
            pos = self.chBaAdd(add)
            self.ResultWriter.reparkEvent(self.SimBroker.t_act, add, self.ChargingStationId, False, self.ChBaMaxPower[pos])

        # update charging desire for every vehicle in the bays and the queue
        CD_Queue = []
        CD_Bays  = []
        for vehicle in self.Queue:
            CD_Queue.append(self.chargingDesire(vehicle))
        for vehicle in self.ChBaVehicles:
            if isinstance(vehicle, components.Vehicle):
                CD_Bays.append(self.chargingDesire(vehicle))
            else:
                CD_Bays.append(-float('inf')) # shouldn't be used at all later

        ''' sorting approach 2'''
        # sort vehicles based on their charging desire and add them to charging bays or queue. Make sure, that you don't shuffle vehicles within the charging bays.
        if len(self.Queue) > 0: # we only need to sort, if we have cars which are not plugged in. If that is the case, every entry in the self.ChBaVehicles list should be of type Vehicle
            CD_merged = CD_Bays + CD_Queue 
            allVehicles = self.ChBaVehicles + self.Queue

            for i in range(0,len(CD_merged)):# change sign to make sorting descending
                CD_merged[i] = -1* CD_merged[i]
            idx_sorted = np.argsort(CD_merged, kind= 'stable')
            n = self.ChBaNum
            idx_Bay_new = idx_sorted[:n]    #this are the vehicles, which should be plugged in
            idx_Bay_newStable = []          #this is a stable indice list of vehicles which are plugged in
            idx_Bay_old = range(0,n)        #this is a indice list of vehicles which have been charging before
            idx = np.isin(element = idx_Bay_old, test_elements=idx_Bay_new) # if true, the vehicle in the old list of vehicles in charging bays is also in the new
            idx_inv = np.isin(element = idx_Bay_new, test_elements=idx_Bay_old) # if false, the vehicle of the new list of vehicles in the charging bays have not been in the old (and should be added)
            j = 0   # variable, read index in idx_inv
            # now go through all vehicles which should be in the charging bays. If vehicle stays in charging bay, just add the index i. If vehicle is removed from bay, choose first entry which should be added from queue.
            for i in range(0, n):
                if idx[i]:
                    idx_Bay_newStable.append(i)
                else:
                    while idx_inv[j] == True: # determines, which element in new sorted bays is added from queue
                        j+=1
                    idx_inv[j] = True
                    idx_Bay_newStable.append(idx_Bay_new[j])
                    num = idx_Bay_new[j]
                    self.ResultWriter.reparkEvent(self.SimBroker.t_act, allVehicles[num], self.ChargingStationId, False, self.ChBaMaxPower[j])
            idx_Queue_new_bool = np.isin(element = range(0,len(CD_merged)), test_elements = idx_Bay_newStable, invert=True) # these are the vehicles which are now in the queue
            idx_Queue_new = [] # this is a list to save their indices
            for i in range(0, len(idx_Queue_new_bool)):
                if idx_Queue_new_bool[i]:
                    idx_Queue_new.append(i)

            idx_from_Bay_to_Queue = np.isin(element = idx_Queue_new, test_elements = range(0, len(self.ChBaVehicles))) # this is to determine if a vehicle was reparked from Bay to Queue
            self.ChBaVehicles = []
            self.Queue = []

            for i in idx_Bay_newStable:
                self.ChBaVehicles.append(allVehicles[i])

            for i in range(0, len(idx_Queue_new)):
                j = idx_Queue_new[i]
                if allVehicles[j] != False:
                    self.Queue.append(allVehicles[j])
                    if idx_from_Bay_to_Queue[i]:
                        self.ResultWriter.reparkEvent(self.SimBroker.t_act, allVehicles[num], self.ChargingStationId, True, 0)

    def chargingDesire(self, v: Vehicle):
        # TODO update this for use with new max charging power method (when changing to realisitic charging profiles)
        if not self.SimBroker.t_act >= v.VehicleDesEnd:
            P_max = min([self.ChBaMaxPower_abs, v.VehicleMaxPower])
            f1 = v.VehicleDesEngy - v.VehicleEngy # fraction part 1
            f2 = (v.VehicleDesEnd - self.SimBroker.t_act) * P_max / 3.6e3
            CD = f1/f2
        else:
            CD = float("inf")
        v.ChargingDesire = CD #this value is saved in the object as its passed by reference
        return CD
    
    def updateFromDerms(self, GridPowerLower: float, GridPowerUpper: float) -> None:
        self.GridPowerLower = GridPowerLower
        self.GridPowerUpper = GridPowerUpper

    def updateFromPhySim(self, CesSoc: float):
        pass
        #self.BtmsEn = self.BtmsSize * CesSoc

    def updateVehicleStatesAndWriteStates(self, ChBaPower, timestep):
        # reset energy and time lag
        self.EnergyLagSum = 0
        self.TimeLagSum = 0

        for i in range(0, len(self.ChBaVehicles)):
            if isinstance(self.ChBaVehicles[i], components.Vehicle):
                # calculate maximum charging power possible in period under energy conservation
                possiblePower = self.ChBaVehicles[i].getMaxChargingPower(
                    timestep)
                # save vehicle state of current time step
                self.ResultWriter.updateVehicleStates(
                    t_act=self.SimBroker.t_act, vehicle=self.ChBaVehicles[i], ChargingStationId=self.ChargingStationId, QueueOrBay=False, ChargingPower=ChBaPower[i], possiblePower=possiblePower)
                # add energy to the vehicle - this is the state for t_act + timestep
                self.ChBaVehicles[i].addPower(ChBaPower[i], timestep)
                # update energy and time lag for the next time step
                self.EnergyLagSum += self.ChBaVehicles[i].updateEnergyLag(
                    self.SimBroker.t_act + timestep)
                self.TimeLagSum += self.ChBaVehicles[i].updateTimeLag(
                    self.SimBroker.t_act + timestep)

        for x in self.Queue:
            # calculate maximum charging power possible in period under energy conservation
            possiblePower = self.ChBaVehicles[i].getMaxChargingPower(
                timestep)
            # save vehicle state of current time step
            self.ResultWriter.updateVehicleStates(
                t_act=self.SimBroker.t_act, vehicle=self.ChBaVehicles[i], ChargingStationId=self.ChargingStationId, QueueOrBay=False, ChargingPower=ChBaPower[i], possiblePower=possiblePower)
            # update energy and time lag
            self.EnergyLagSum += x.updateEnergyLag(
                self.SimBroker.t_act + timestep)
            self.TimeLagSum += self.ChBaVehicles[i].updateTimeLag(
                self.SimBroker.t_act + timestep)

    def distributeChargingPowerToVehicles(self, timestep, P_max):
        self.ChBaPower = [] # delete charging power of previous timestep
        CD_Bays = [] # list of charging desire of the vehicles in bays
        for x in self.ChBaVehicles:
            self.ChBaPower.append(0) # make list of charging power with corresponding size
            if isinstance(x, components.Vehicle):
                CD_Bays.append(-1* x.ChargingDesire) # multiply with -1 to have an sorted descending list
            else:
                CD_Bays.append(float('nan')) # add nan if no vehicle is in Bay
        idx_Bays = np.argsort(CD_Bays) # this is a list of indices of the vehicles sorted by their descending charging desire

        for j in idx_Bays: # go through charging bays sorted by their charging desire 
            if isinstance(self.ChBaVehicles[j], components.Vehicle): # only assign charging power, if a vehicle is in the bay
                maxPower = min([self.ChBaMaxPower[j], self.ChBaVehicles[j].getMaxChargingPower(timestep)]) # the maximum power of the current bay is the minimum of the chargingbay max power and the vehicle max power
                sumPowers = sum(self.ChBaPower)
                if  sumPowers + maxPower <= P_max: # test if maxPower of current bay can be fully added
                    self.ChBaPower[j] = maxPower
                elif sumPowers < P_max: # test if intermediate value can be added
                    self.ChBaPower[j] = P_max - sumPowers
                else: # if sumPowers is already bigger than P_max, we don't add charging power
                    break
        return sum(self.ChBaPower) # P_ChargeDelivered

    def step(self, timestep): # call with t_act = SimBroker.t_act
        '''TEMPLATE FOR OTHER CONTROLLERS:'''
        pass
    
        '''repark vehicles based on tehir charging desire with the parent method'''
        self.repark()

        '''insert here the control action'''

        '''assign values to:
        self.ChBaPower
        self.BtmsPower
        DONE: self.PowerDesire # for DERMS
        DONE: self.BtmsPowerDesire # for DERMS
        '''

        '''Write chargingStation states for k in ResultWriter'''
        self.ResultWriter.updateChargingStationState(
            self.SimBroker.t_act, self)

        '''# update BTMS state for k+1'''
        # BTMS
        self.BtmsAddPower(self.P_BTMS, timestep)

        '''write vehicle states for k in ResultWriter and update vehicle states for k+1'''
        # Vehicles
        self.updateVehicleStatesAndWriteStates(self.ChBaPower, timestep)

        '''determine power desire for next time step
                this must be done after Vehicle and BTMS states are updated, so that charging curves can be taken into account'''
        PowerDesire = 0
        for i in range(0,len(self.ChBaVehicles)):
            if self.ChBaVehicles[i] != False:
                PowerDesire += min([self.ChBaVehicles[i].getMaxChargingPower(timestep), self.ChBaMaxPower[i]])

        self.PowerDesire = PowerDesire
        self.BtmsPowerDesire = self.getBtmsMaxPower(timestep)

        '''release vehicles when full and create control outputs'''
        self.resetOutput()
        r1 = self.chBaReleaseThresholdAndOutput()
        r2 = self.queueReleaseThresholdAndOutput()
        released_Vehicles = r1 + r2
        # add release events
        for x in released_Vehicles:
            self.ResultWriter.releaseEvent(self.SimBroker.t_act, x, self.ChargingStationId)

        '''checks'''
        if len(self.ChBaVehicles)!=self.ChBaNum:
            raise ValueError("Size of ChargingBay List shouldn't change")