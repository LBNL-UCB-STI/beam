import components
from components.ResultWriter import ResultWriter
from components.SimBroker import SimBroker
import logging

class ControlWrapper:
    # A Wrapper for the Limit Controller

    def __init__(self, initMpc, t_start, timestep_intervall, result_directory, RideHailDepotId, ChBaMaxPower, ChBaParkingZoneId, ChBaNum, path_BeamPredictionFile, dtype_Predictions, t_max)  -> None:
        ''' choose charging station type '''
        if initMpc == False:
            import components.ChaDepLimCon as chargingStationClass
        elif initMpc == True:
            import components.ChaDepMpcBase as chargingStationClass

        ''' init SimBroker Dummy , VehicleGenerator and ResultWriter'''
        self.SimBroker          = components.GeminiWrapper.SimBrokerDummy(t_start, timestep_intervall) # this is only a central clock
        self.VehicleGenerator   = components.GeminiWrapper.VehicleGeneratorBeam()
        self.ResultWriter       = components.ResultWriter(result_directory, chargingStationId=str(RideHailDepotId))

        '''create charging station object'''
        self.ChargingStation = chargingStationClass(ChargingStationId=RideHailDepotId, ResultWriter=self.ResultWriter, SimBroker = self.SimBroker, ChBaMaxPower=ChBaMaxPower, ChBaParkingZoneId=ChBaParkingZoneId, ChBaNum = ChBaNum ,calcBtmsGridProp = True)
        
        '''For MPC: initializations'''
        if isinstance(self.ChargingStation, components.ChaDepMpcBase):
            '''generate predictions TODO: need old result file for this '''
            self.ChargingStation.generatePredictions(path_BeamPredictionFile, dtype_Predictions, timestep_intervall, addNoise = True)
            # perform btms size optimization
            a = 20 / 30 * (t_max - self.SimBroker.t_act) / 3600 / 24 # demand charge per day
            P_free_Ratio = 0    # free power, after which demand charge is applied, as ratio to avg power
            b = 300/5000        # btms cost per cycle per kWh (price per kWh/ possible cycles)
            c = 0.15            # electricity cost per kWh

            avgPower = sum(self.ChargingStation.PredictionPower*timestep_intervall) / (max(self.ChargingStation.PredictionTime) - min(self.ChargingStation.PredictionTime))
            P_free = P_free_Ratio * avgPower
            
            self.ChargingStation.determineBtmsSize(self.SimBroker.t_act, t_max, timestep_intervall, a, b, c, P_free)
            # TODO: add here logging information

            # save btms size optimization results
            # TODO save btms size optimization results

            '''create optimal day ahead plan'''
            a = 50/30 * ((SimBroker.t_max - SimBroker.t_act)/3.6e3) / 24  # demand charge cost
            # free power, after which demand charge is applied, as ratio to avg power
            P_free_Ratio = 0
            b = 300/5000                    # btms degradation cost
            c = 0.15                        # electricity cost
            d_param = 10                    # waiting time cost of a vehicle in $/h
            P_chAvg = 100                   # average charging speed in kW of an vehicle
            beta = 0.15                     # bandwith for energy level curve
            # if choosen different from None, constraints to enforce c rate are applied
            cRating = None

            avgPower = sum(self.ChargingStation.PredictionPower*timestep_intervall) / (max(self.ChargingStation.PredictionTime) - min(self.ChargingStation.PredictionTime))
            P_free = P_free_Ratio * avgPower

            self.ChargingStation.planning(self.SimBroker.t_act, t_max, timestep_intervall, a, b, c, d_param, P_free, P_chAvg, beta, cRating)

            # TODO: add here logging information
            # save optimal day ahead plan (TODO)

        '''write chargingStationProperties to ResultWriter'''
        self.ResultWriter.saveChargingStationProperties([self.ChargingStation]) # this function takes a list of charging station objects as input argument


    def synchronizeVehiclesAtStation(self, vehicleIdsAtStation, t_act) -> None:
        # this is a routine to remove vehicles from the charging station list ChBaVehicles and Queue, which are not at the charging station anymore (in the list of vehicleIds we got from BEAM)

        # make a list of vehicle ids which are in the charging station
        vehicleInDepot = []
        for vehicle in self.ChargingStation.ChBaVehicles:
            vehicleInDepot.append(vehicle.vehicleId)
        for vehicle in self.ChargingStation.Queue:
            vehicleInDepot.append(vehicle.vehicleId)
        
        for vehicle in vehicleIdsAtStation:
            # make a list of vehicles which are not in the charging station and need to be popped out
            pop_out = []
            if vehicle not in vehicleInDepot:
                pop_out.append(vehicle.vehicleId)
                logging.warning("Vehicle with id " + str(vehicle) + " is not in the charging station. This should not happen. But this vehicle will be removed now.")
        
        for vehicleId in pop_out:
            # pop these vehicles out of the charging station
            for i in range(0, len(self.ChargingStation.ChBaVehicles)):
                if self.ChargingStation.ChBaVehicles[i].vehicleId == vehicleId:
                    removedVehicle = self.ChargingStation.ChBaVehicles[i]
                    self.ChargingStation.ChBaVehicles[i] = False # set the list at the index to false, so that the vehicle is removed
                    break
            for i in range(0, len(self.ChargingStation.Queue)):
                if self.ChargingStation.Queue[i].vehicleId == vehicleId:
                    removedVehicle = self.ChargingStation.Queue.pop(i)
                    break
            # write the removed vehicle to the result file
            ResultWriter.forcedReleaseEvent(t_act, removedVehicle, self.ChargingStation.ChargingStationId)

    def arrival(self, VehicleId, VehicleType, VehicleArrival, VehicleDesEnd, VehicleEngyInKwh, VehicleDesEngyInKwh, VehicleMaxEngy, VehicleMaxPower, t_act) -> None:
        # generate a vehicle object
        vehicle = self.VehicleGenerator.generateVehicle(VehicleId, VehicleType, VehicleArrival, VehicleDesEnd, VehicleEngyInKwh, VehicleDesEngyInKwh, VehicleMaxEngy, VehicleMaxPower)
        # add vehicle to charging station
        self.ChargingStation.arrival(vehicle, t_act) # we did not increase time in SimBroker, but this only triggers the arrival function with the result writer, which uses t_act


    def step (self, timestep, t_act, GridPowerUpper, GridPowerLower, BtmsEnergy):
        # add something with SimBrokerDummy here to update time and choose correct iteration
        self.SimBroker.updateTime(t_act)
        
        # update from DERMS and PyDSS
        self.ChargingStation.updateFromDerms(GridPowerLower, GridPowerUpper)
        self.updateFromPhySim(BtmsEnergy)
        self.ChargingStation.step(timestep)

        vehicles, power, release = self.ChargingStation.getControlOutput()

        return vehicles, power, release

    def saveResults(self):
        self.ResultWriter.save()

    #####################################
    # MAKE SURE TO SAVE RESULTS IN THE END WHEN SHUTTING DOWN FEDERATE
    #####################################

    