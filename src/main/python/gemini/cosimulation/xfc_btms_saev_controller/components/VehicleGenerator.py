import pandas as pd
import numpy as np
import components

class VehicleGenerator:

    '''This is the vehicle generator for the stand-alone version.'''

    def __init__(self, path_Sim, dtype_Sim, path_DataBase):

        # find out which vehicles are in the simulation, and map them with their vehicleTypes.
        # this is only saved in RefuelSessionEvents, but a charging session starts with a PlugInEvent, which is why need to create this map.
        self.SimRes     = pd.read_csv(path_Sim, dtype = dtype_Sim, index_col= "time") # save length of pd dataframe, time is set as index!
        self.SimRes     = self.SimRes.sort_index()  # make sure that inputs are ascending
        length     = len(self.SimRes)     # save length of pd dataframe

        self.vehicles = pd.DataFrame(columns = ["vehicle", "vehicleType"]) # create empty dataframe for vehicles
        # only RefuelSessionEvents contain this data:
        idx = self.SimRes["type"] == "RefuelSessionEvent"
        for i in range(0, length):
            if idx.iloc[i] == True: # if this is a RefuelSessionEvent
                vehicle_act = self.SimRes["vehicle"].iloc[i]
                if not self.vehicles["vehicle"].isin( [vehicle_act] ).any():
                    dict = {"vehicle": self.SimRes["vehicle"].iloc[i],
                            "vehicleType": self.SimRes["vehicleType"].iloc[i]
                            }
                    self.vehicles = self.vehicles.append(dict, ignore_index=True)
        self.vehicles = self.vehicles.astype({'vehicle': 'int64', 'vehicleType': 'category'})
        self.vehicles = self.vehicles.set_index("vehicle")
        self.vehicles = self.vehicles.sort_index()
        
        # leave self.SimRes open to find next data the desired charging energy
        
        # load now the vehicletype database. Here is specified, what data shall be loaded.
        useCols = ["vehicleTypeId", "primaryFuelConsumptionInJoulePerMeter", "primaryFuelCapacityInJoule", "chargingCapability"]
        dtype_DataBase = {"vehicleTypeId": "category", "primaryFuelConsumptionInJoulePerMeter": "float64", "primaryFuelCapacityInJoule": "float64", "chargingCapability": "category"}
        self.DataBase = pd.read_csv(path_DataBase, usecols=useCols, dtype=dtype_DataBase, index_col="vehicleTypeId")

        pass

    def generateVehicleSO(self, df_slice: pd.DataFrame):
        # for the Stand-Alone (SO) version
        # generate here the vehicle, based on the df_slice with "ChargingPlugInEvent"
        # (the slice of the event) which is given by the SimBroker.
        if df_slice.type != "ChargingPlugInEvent":
            raise ValueError("You didn't pass a charging plug-in event to generateVehicle")
        
        VehicleId           = df_slice["vehicle"]
        VehicleType         = self.vehicles.loc[VehicleId, "vehicleType"] # use map to find out the vehicleType
        VehicleArrival      = df_slice.name
        VehicleEngy         = df_slice["primaryFuelLevel"] / 3.6e6 # conversion to kWh
        VehicleMaxEngy      = self.DataBase.loc[VehicleType, "primaryFuelCapacityInJoule"] / 3.6e6 # conversion to kWh
        # generate here the maximum power of the vehicle:
        chargingCap = self.DataBase.loc[VehicleType, "chargingCapability"]
        VehicleMaxPower = components.chargingCapFromString(chargingCap)
        
        #for desired end and desired energy, we need to find the corresponding RefuelSessionEvent
        # this is after ChargingPlugInEvent.
        # therfore: time must be greater equals than VehicleArrival Time, type must be RefuelSessionEvent, VehicleId must be the same. Furthermore, this must be the first entry. 
        try:
            RefuelSessionEvent = self.SimRes[np.logical_and(np.logical_and(self.SimRes.index >= VehicleArrival, self.SimRes.type == "RefuelSessionEvent"), self.SimRes.vehicle == VehicleId)].iloc[0] # select first row
            VehicleDesEnd       = RefuelSessionEvent.name # this is the time at which refuel session event is finished
            VehicleDesEngy      = RefuelSessionEvent.fuel / 3.6e6 + VehicleEngy # this is the desired state of energy at the end of the charging event
            BeamDesignatedParkingZoneId = RefuelSessionEvent.parkingZoneId
        except: # if we are at the end of the file, we don't want errors from events which aren't finished.
            VehicleDesEnd               = 0
            VehicleDesEngy              = 0 
            BeamDesignatedParkingZoneId    = False
            print("VehicleGenerator: did not find associated RefuelSession Event. Set Energy to 0")
        
        Vehicle = components.Vehicle(VehicleId, VehicleType, VehicleArrival, VehicleDesEnd, VehicleEngy, VehicleDesEngy, VehicleMaxEngy, VehicleMaxPower, ParkingZoneId=BeamDesignatedParkingZoneId)

        return Vehicle