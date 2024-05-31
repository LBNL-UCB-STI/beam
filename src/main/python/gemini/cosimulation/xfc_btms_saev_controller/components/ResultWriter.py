
import string
import os
from typing import List
import pandas as pd
from components import ChaDepParent
from components import Vehicle
import components
import cvxpy as cp

class ResultWriter:

    def __init__(self, directory: string, format: string = ".csv", saveInGemini = False, chargingStationId: string = 'ID') -> None:
        self.directory = directory
        os.makedirs(self.directory, exist_ok=True)
        self.ChargingStationState_Filename      = os.path.join(self.directory, "ChargingStationState"       + format)
        self.MpcStats_Filename                  = os.path.join(self.directory, "MpcStats"                   + format)
        self.Events_Filename                    = os.path.join(self.directory, "Events"                     + format)
        self.VehicleStates_Filename             = os.path.join(self.directory, "VehicleStates"              + format)
        self.ChargingStationProperties_Filename = os.path.join(self.directory, "ChargingStationProperties"  + format)

        # for speed increase, we wanna save the dict form data in list and create the dataframe at the end
        self.ChargingStationStates_list         = []
        self.MpcStats_list                      = []
        self.Events_list                        = []
        self.VehicleStates_list                 = []
        self.ChargingStationProperties_list     = []

        '''column names of the dataframes'''
        self.ChargingStationStates          = pd.DataFrame(columns= [
            "time", 
            "ChargingStationID", 
            "BaysVehicleIds", 
            "BaysChargingPower", 
            "TotalChargingPower", 
            "BaysChargingDesire",
            "BaysNumberOfVehicles", 
            "QueueVehicleIds", 
            "QueueChargingDesire", 
            "QueueNumberOfVehicles", 
            "BtmsPower",
            "BtmsSoc",
            "BtmsEnergy", 
            "TotalChargingPowerDesire", 
            "GridPower", 
            "GridPowerUpper", 
            "GridPowerLower", 
            "PowerDesire", 
            "BtmsPowerDesire", 
            "EnergyLagSum", 
            "TimeLagSum",
        ])
        self.MpcStats                         = pd.DataFrame(columns= [
            "time",
            "ChargingStationID",
            "Status",
            "Iterations",
            "OptimalValue",
            "SetupTime",
            "SolveTime",
            "SolverName",
            "sum_t1",
            "sum_t2",
            "T1",
            "T2",
        ])  
        self.Events                         = pd.DataFrame(columns=[
            "time", 
            "Event", 
            "ChargingStationId", 
            "VehicleId", 
            "QueueOrBay", 
            "ChargingDesire", 
            "VehicleType", 
            "VehicleArrival", 
            "VehicleDesiredEnd", 
            "VehicleEnergy", 
            "VehicleDesiredEnergy", 
            "VehicleSoc", 
            "VehicleMaxEnergy", 
            "VehicleMaxPower", 
            "ChargingBayMaxPower"
        ])
        self.VehicleStates                   = pd.DataFrame(columns=[
            "time", 
            "VehicleId", 
            "ChargingStationId", 
            "QueueOrBay", 
            "ChargingPower", 
            "possiblePower",  
            "ChargingDesire", 
            "VehicleDesiredEnd", 
            "VehicleEnergy", 
            "VehicleDesiredEnergy", 
            "VehicleSoc", 
            "EnergyLag", 
            "TimeLag"
        ])

        ####
        self.chargingStationProperties           = pd.DataFrame(columns = [
            "ChargingStationId", 
            "BtmsSize", 
            "BtmsC", 
            "BtmsMaxPower", 
            "BtmsMaxSoc", 
            "BtmsMinSoc", 
            "ChBaNum", 
            "ChBaMaxPower", 
            "ChBaMaxPower_abs",
            "ChBaParkingZoneId", 
            "GridPowerMax_Nom",
        ])

    def reset(self):
        list1 = self.ChargingStationStates.columns
        list2 = self.MpcStats.columns
        list3 = self.Events.columns
        list4 = self.VehicleStates.columns

        self.ChargingStationStates = pd.DataFrame(columns=list1)
        self.MpcStats = pd.DataFrame(columns=list2)
        self.Events = pd.DataFrame(columns=list3)
        self.VehicleStates = pd.DataFrame(columns=list4)

        self.ChargingStationStates_list = []
        self.MpcStats_list = []
        self.Events_list = []
        self.VehicleStates_list = []

    def save(self):
        # make from all list a dataframe
        self.ChargingStationStates = pd.DataFrame(self.ChargingStationStates_list)
        self.MpcStats = pd.DataFrame(self.MpcStats_list)
        self.Events = pd.DataFrame(self.Events_list)
        self.VehicleStates = pd.DataFrame(self.VehicleStates_list)
        # chargingStationProperties is already a dataframe

        # save the three DataFrames
        saveDataFrames = [self.ChargingStationStates, self.MpcStats, self.Events, self.VehicleStates, self.chargingStationProperties]
        saveFileNames  = [self.ChargingStationState_Filename, self.MpcStats_Filename, self.Events_Filename, self.VehicleStates_Filename, self.ChargingStationProperties_Filename] 
        for i in range(0,len(saveDataFrames)):
            if i<4:
                save = saveDataFrames[i].set_index("time")
            else:
                save = saveDataFrames[i]
            pd.DataFrame.to_csv( save, saveFileNames[i]) # the index is just set to time before saving.

    # add now all the events which could happen and assign the entries to the differnt dataframes

    def reparkEvent(self, t_act, Vehicle: Vehicle, ChargingStationId, QueueOrBay, ChargingBayMaxPower):
        if QueueOrBay == True:
            QueueOrBay = 'Queue'
        else:
            QueueOrBay = 'Bay'
        # self.Events = self.Events.append(
        #     {"time": t_act, "Event": "ReparkEvent", "ChargingStationId": ChargingStationId, "VehicleId": Vehicle.VehicleId, "QueueOrBay": QueueOrBay, "ChargingDesire": Vehicle.ChargingDesire, "VehicleType": Vehicle.VehicleType, "VehicleArrival": Vehicle.VehicleArrival, "VehicleDesiredEnd": Vehicle.VehicleDesEnd, "VehicleEnergy": Vehicle.VehicleEngy, "VehicleDesiredEnergy": Vehicle.VehicleDesEngy, "VehicleSoc": Vehicle.VehicleSoc, "VehicleMaxEnergy": Vehicle.VehicleMaxEngy, "VehicleMaxPower": Vehicle.VehicleMaxPower, "ChargingBayMaxPower": ChargingBayMaxPower
        # }, ignore_index=True)
        # append dict to list
        self.Events_list.append(
            {"time": t_act, "Event": "ReparkEvent", "ChargingStationId": ChargingStationId, "VehicleId": Vehicle.VehicleId, "QueueOrBay": QueueOrBay, "ChargingDesire": Vehicle.ChargingDesire, "VehicleType": Vehicle.VehicleType, "VehicleArrival": Vehicle.VehicleArrival, "VehicleDesiredEnd": Vehicle.VehicleDesEnd, "VehicleEnergy": Vehicle.VehicleEngy, "VehicleDesiredEnergy": Vehicle.VehicleDesEngy, "VehicleSoc": Vehicle.VehicleSoc, "VehicleMaxEnergy": Vehicle.VehicleMaxEngy, "VehicleMaxPower": Vehicle.VehicleMaxPower, "ChargingBayMaxPower": ChargingBayMaxPower
        })

    def arrivalEvent(self, t_act, Vehicle: Vehicle, ChargingStationId):
        # self.Events = self.Events.append(
        #     {"time": t_act, "Event": "ArrivalEvent", "ChargingStationId": ChargingStationId, "VehicleId": Vehicle.VehicleId, "QueueOrBay": "", "ChargingDesire": Vehicle.ChargingDesire, "VehicleType": Vehicle.VehicleType, "VehicleArrival": Vehicle.VehicleArrival, "VehicleDesiredEnd": Vehicle.VehicleDesEnd, "VehicleEnergy": Vehicle.VehicleEngy, "VehicleDesiredEnergy": Vehicle.VehicleDesEngy, "VehicleSoc": Vehicle.VehicleSoc, "VehicleMaxEnergy": Vehicle.VehicleMaxEngy, "VehicleMaxPower": Vehicle.VehicleMaxPower, "ChargingBayMaxPower": float("nan")
        # }, ignore_index=True)
        # append dict to list
        self.Events_list.append(
            {"time": t_act, "Event": "ArrivalEvent", "ChargingStationId": ChargingStationId, "VehicleId": Vehicle.VehicleId, "QueueOrBay": "", "ChargingDesire": Vehicle.ChargingDesire, "VehicleType": Vehicle.VehicleType, "VehicleArrival": Vehicle.VehicleArrival, "VehicleDesiredEnd": Vehicle.VehicleDesEnd, "VehicleEnergy": Vehicle.VehicleEngy, "VehicleDesiredEnergy": Vehicle.VehicleDesEngy, "VehicleSoc": Vehicle.VehicleSoc, "VehicleMaxEnergy": Vehicle.VehicleMaxEngy, "VehicleMaxPower": Vehicle.VehicleMaxPower, "ChargingBayMaxPower": float("nan")
            })

    def releaseEvent(self, t_act, Vehicle: Vehicle, ChargingStationId):
        # self.Events = self.Events.append(
        #     {"time": t_act, "Event": "ReleaseEvent", "ChargingStationId": ChargingStationId, "VehicleId": Vehicle.VehicleId, "QueueOrBay": "", "ChargingDesire": float("NaN"), "VehicleType": Vehicle.VehicleType, "VehicleArrival": Vehicle.VehicleArrival, "VehicleDesiredEnd": Vehicle.VehicleDesEnd, "VehicleEnergy": Vehicle.VehicleEngy, "VehicleDesiredEnergy": Vehicle.VehicleDesEngy, "VehicleSoc": Vehicle.VehicleSoc, "VehicleMaxEnergy": Vehicle.VehicleMaxEngy, "VehicleMaxPower": Vehicle.VehicleMaxPower, "ChargingBayMaxPower": float("nan")
        # }, ignore_index=True)
        # append dict to list
        self.Events_list.append(
            {"time": t_act, "Event": "ReleaseEvent", "ChargingStationId": ChargingStationId, "VehicleId": Vehicle.VehicleId, "QueueOrBay": "", "ChargingDesire": float("NaN"), "VehicleType": Vehicle.VehicleType, "VehicleArrival": Vehicle.VehicleArrival, "VehicleDesiredEnd": Vehicle.VehicleDesEnd, "VehicleEnergy": Vehicle.VehicleEngy, "VehicleDesiredEnergy": Vehicle.VehicleDesEngy, "VehicleSoc": Vehicle.VehicleSoc, "VehicleMaxEnergy": Vehicle.VehicleMaxEngy, "VehicleMaxPower": Vehicle.VehicleMaxPower, "ChargingBayMaxPower": float("nan")
            })

    def forcedReleaseEvent(self, t_act, Vehicle: Vehicle, ChargingStationId):
        # TODO: Did I use this?
        # self.Events = self.Events.append(
        #     {"time": t_act, "Event": "ForcedReleaseEvent", "ChargingStationId": ChargingStationId, "VehicleId": Vehicle.VehicleId, "QueueOrBay": "", "ChargingDesire": float("NaN"), "VehicleType": Vehicle.VehicleType, "VehicleArrival": Vehicle.VehicleArrival, "VehicleDesiredEnd": Vehicle.VehicleDesEnd, "VehicleEnergy": Vehicle.VehicleEngy, "VehicleDesiredEnergy": Vehicle.VehicleDesEngy, "VehicleSoc": Vehicle.VehicleSoc, "VehicleMaxEnergy": Vehicle.VehicleMaxEngy, "VehicleMaxPower": Vehicle.VehicleMaxPower, "ChargingBayMaxPower": float("nan")
        # }, ignore_index=True)
        # append dict to list
        self.Events_list.append(
            {"time": t_act, "Event": "ForcedReleaseEvent", "ChargingStationId": ChargingStationId, "VehicleId": Vehicle.VehicleId, "QueueOrBay": "", "ChargingDesire": float("NaN"), "VehicleType": Vehicle.VehicleType, "VehicleArrival": Vehicle.VehicleArrival, "VehicleDesiredEnd": Vehicle.VehicleDesEnd, "VehicleEnergy": Vehicle.VehicleEngy, "VehicleDesiredEnergy": Vehicle.VehicleDesEngy, "VehicleSoc": Vehicle.VehicleSoc, "VehicleMaxEnergy": Vehicle.VehicleMaxEngy, "VehicleMaxPower": Vehicle.VehicleMaxPower, "ChargingBayMaxPower": float("nan")
            }
        )

    def updateVehicleStates(self, t_act, vehicle: Vehicle, ChargingStationId, QueueOrBay, ChargingPower, possiblePower):
        if QueueOrBay == True:
            QueueOrBay = 'Queue'
        else:
            QueueOrBay = 'Bay'
        # self.VehicleStates = self.VehicleStates.append(
        #     {"time": t_act, "VehicleId": vehicle.VehicleId, "ChargingStationId": ChargingStationId, "QueueOrBay": QueueOrBay , "ChargingPower": ChargingPower, "possiblePower": possiblePower,            "ChargingDesire": vehicle.ChargingDesire, "VehicleDesiredEnd": vehicle.VehicleDesEnd, "VehicleEnergy": vehicle.VehicleEngy, "VehicleDesiredEnergy": vehicle.VehicleDesEngy, "VehicleSoc": vehicle.VehicleSoc, "EnergyLag": vehicle.EnergyLag, "TimeLag": vehicle.TimeLag
        #     }, ignore_index=True)
        self.VehicleStates_list.append(
            {"time": t_act, "VehicleId": vehicle.VehicleId, "ChargingStationId": ChargingStationId, "QueueOrBay": QueueOrBay , "ChargingPower": ChargingPower, "possiblePower": possiblePower,            "ChargingDesire": vehicle.ChargingDesire, "VehicleDesiredEnd": vehicle.VehicleDesEnd, "VehicleEnergy": vehicle.VehicleEngy, "VehicleDesiredEnergy": vehicle.VehicleDesEngy, "VehicleSoc": vehicle.VehicleSoc, "EnergyLag": vehicle.EnergyLag, "TimeLag": vehicle.TimeLag
            }
        )
    
    def updateChargingStationState(self, t_act, ChargingStation: ChaDepParent):
        CD_Bays = []
        VehicleIds = []
        numVehiclesBays = 0
        for x in ChargingStation.ChBaVehicles:
            if x != False:
                CD_Bays.append(x.ChargingDesire)
                VehicleIds.append(x.VehicleId)
                numVehiclesBays += 1
            else:
                CD_Bays.append(float('nan'))
        CD_Queue = []
        VehicleIdsQueue = []
        for x in ChargingStation.Queue:
            CD_Queue.append(x.ChargingDesire)
            VehicleIdsQueue.append(x.VehicleId)

        #make sure that energy and time lag was calculated (in updateVehicleStatesAndWriteStates in ChaDepParent)

        # self.ChargingStationStates = self.ChargingStationStates.append({
        #     "time": t_act, "ChargingStationID": ChargingStation.ChargingStationId, "BaysVehicleIds": VehicleIds, "BaysChargingPower": ChargingStation.ChBaPower, "TotalChargingPower": sum(ChargingStation.ChBaPower), "BaysChargingDesire": CD_Bays, "BaysNumberOfVehicles": numVehiclesBays, "QueueVehicleIds": VehicleIdsQueue, "QueueChargingDesire": CD_Queue, "QueueNumberOfVehicles": len(CD_Queue), "BtmsPower": ChargingStation.P_BTMS, "BtmsSoc": ChargingStation.BtmsSoc(), "BtmsEnergy": ChargingStation.BtmsEn, "TotalChargingPowerDesire": ChargingStation.PowerDesire, "GridPower": ChargingStation.P_Grid, "GridPowerUpper": ChargingStation.GridPowerUpper, "GridPowerLower": ChargingStation.GridPowerLower,  "PowerDesire": ChargingStation.PowerDesire, "BtmsPowerDesire": ChargingStation.BtmsPowerDesire, "EnergyLagSum": ChargingStation.EnergyLagSum, "TimeLagSum": ChargingStation.TimeLagSum
        # }, ignore_index=True)
        self.ChargingStationStates_list.append(
            {"time": t_act, "ChargingStationID": ChargingStation.ChargingStationId, "BaysVehicleIds": VehicleIds, "BaysChargingPower": ChargingStation.ChBaPower, "TotalChargingPower": sum(ChargingStation.ChBaPower), "BaysChargingDesire": CD_Bays, "BaysNumberOfVehicles": numVehiclesBays, "QueueVehicleIds": VehicleIdsQueue, "QueueChargingDesire": CD_Queue, "QueueNumberOfVehicles": len(CD_Queue), "BtmsPower": ChargingStation.P_BTMS, "BtmsSoc": ChargingStation.BtmsSoc(), "BtmsEnergy": ChargingStation.BtmsEn, "TotalChargingPowerDesire": ChargingStation.PowerDesire, "GridPower": ChargingStation.P_Grid, "GridPowerUpper": ChargingStation.GridPowerUpper, "GridPowerLower": ChargingStation.GridPowerLower,  "PowerDesire": ChargingStation.PowerDesire, "BtmsPowerDesire": ChargingStation.BtmsPowerDesire, "EnergyLagSum": ChargingStation.EnergyLagSum, "TimeLagSum": ChargingStation.TimeLagSum
        }
        )

    def updateMpcStats(self, t_act, ChargingStation , prob: cp.Problem, T1: cp.Variable , T2: cp.Variable):
        if not isinstance(ChargingStation, components.ChaDepMpcBase):
            raise ValueError("ResultWriter.MpcStats: ChargingStation is not of type ChaDepMpcBase")
        # self.MpcStats = self.MpcStats.append({
        #     "time": t_act,
        #     "ChargingStationID": ChargingStation.ChargingStationId,
        #     "Status": prob.status,
        #     "Iterations": prob.solver_stats.num_iters,
        #     "OptimalValue": prob.value,
        #     "SetupTime": prob.solver_stats.setup_time,
        #     "SolveTime": prob.solver_stats.solve_time,
        #     "SolverName": prob.solver_stats.solver_name,
        #     "sum_t1": sum(T1.value),
        #     "sum_t2": sum(T2.value),
        #     "T1": T1.value, 
        #     "T2": T2.value,
        # }, ignore_index=True)
        self.MpcStats_list.append(
            {
            "time": t_act,
            "ChargingStationID": ChargingStation.ChargingStationId,
            "Status": prob.status,
            "Iterations": prob.solver_stats.num_iters,
            "OptimalValue": prob.value,
            "SetupTime": prob.solver_stats.setup_time,
            "SolveTime": prob.solver_stats.solve_time,
            "SolverName": prob.solver_stats.solver_name,
            "sum_t1": sum(T1.value),
            "sum_t2": sum(T2.value),
            "T1": T1.value, 
            "T2": T2.value,
            }
        )

    def saveChargingStationProperties(self, chargingStations):
        # chargingStations is a list of chargingStations
        for x in chargingStations:
            self.chargingStationProperties = self.chargingStationProperties.append({"ChargingStationId": x.ChargingStationId, "BtmsSize": x.BtmsSize, "BtmsC": x.BtmsC, "BtmsMaxPower": x.BtmsMaxPower, "BtmsMaxSoc": x.BtmsMaxSoc, "BtmsMinSoc": x.BtmsMinSoc, "ChBaNum": x.ChBaNum, "ChBaMaxPower": x.ChBaMaxPower, "ChBaMaxPower_abs": x.ChBaMaxPower_abs, "ChBaParkingZoneId": x.ChBaParkingZoneId, "GridPowerMax_Nom": x.GridPowerMax_Nom}, ignore_index=True)
            
    # TODO: add some dataframe like planning results