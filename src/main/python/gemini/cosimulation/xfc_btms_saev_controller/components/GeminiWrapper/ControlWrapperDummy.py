import components
class ControlWrapperDummy():

    '''This dummy wouldnt work like this, need to be made functional
    What is this? Probably not necessary anymore.'''

    def __init__(self) -> None:
        self.Vehicles = [] # list for vehicle ids
        self.stay_length = []
    
    def departure(self, vehicleId, primaryFuelLevelInJoules, departureTime) -> None:
        # departure of vehicles
        pass

    def arrival(self, vehicleId, vehicleType, arrivalTime, desiredDepartureTime, primaryFuelLevelinJoules, desiredFuelLevelInJoules) -> None:
        # arrival of vehicles
        self.Vehicles.append(vehicleId)
        self.stay_length.append(0)

    def updateEnergyUse(self, vehicleIds, primaryFuelLevelInJoules):
        # pass primaryFuelLevelInJoule of every vehicle of the last timestep to update controller memory with their actual used energy

        # calculate energy use of last period
        # for vehicleID:
        #     energy_used = updated energy - arrival energy or departure energy - arrival energy

        # update also decision on how to charge/discharge BTMS in the last time period. 
        pass

    def step (self, timestep):
        power = 500
        delete = []
        release = []
        for i in range(0, len(self.Vehicles)):
            self.stay_length[i] += 1
            if self.stay_length[i] > 2: # release vehicles after 3 charging iterations
                delete.append(i)
                release.append(True)
            else:
                release.append(False)

        control_command_list = []
        for i in range(0, len(self.Vehicles)):
            control_command_list.append({
                'vehicleId': str(self.Vehicles[i]),
                'power': str(power),
                'release': str(release[i])
            })
        
        # delete release vehicles from charging station
        for i in range(0, len(delete)):
            self.stay_length.pop(delete[i] - i)
            self.Vehicles.pop(delete[i] - i)

        # output power allowance for the next step for new arrived vehicles
        power_allowance = 0

        return control_command_list, power_allowance

    def sendPowerToDERMS():
        pass

    def sendPowerToGrid():
        pass

    def updatePowerLimitsFromDERMS():
        pass

    def updateCESfromGrid():
        pass