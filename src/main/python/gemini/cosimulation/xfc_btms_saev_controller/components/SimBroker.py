'''
Datatypes template for Input File:
dtype = {
       'time': 'int64', 
       'type': 'category', 
       'vehicle': 'int64', 
       'parkingTaz': 'category', #
       'chargingPointType': 'category', 
       'primaryFuelLevel': 'float64', #
       'mode': 'category', 
       'currentTourMode': 'category', 
       'vehicleType': 'category', 
       'arrivalTime': 'float64', #
       'departureTime': 'float64', # 
       'linkTravelTime': 'string', 
       'primaryFuelType': 'category', 
       'parkingZoneId': 'category',
       'duration': 'float64' #
}

'''
import pandas as pd
import logging

class SimBroker:

    def __init__(self, path, dtype):

        self.SimRes     = pd.read_csv(path, dtype = dtype, index_col= "time") # save length of pd dataframe, time is set as index!
        self.SimRes     = self.SimRes.sort_index()  # make sure that inputs are ascending
        self.length     = len(self.SimRes)     # save length of pd dataframe
        self.i          = 0                    # line in result dataframe which is currently read
        self.t_act      = self.SimRes.index[self.i]
        self.t_max      = max(self.SimRes.index)
        self.iteration  = -1                    # number of iteration which in terms of steps of timestep, first call of step will set timestep to 0 for first simulation iteration.
        '''
        self.t_act      = t_Start              # start time of simulation in seconds
        while self.SimRes.index[self.i] < self.t_act:
            self.i      +=1
        '''

    def step(self, timestep):
        self.t_act      += timestep             # update actual time
        i_old = self.i                          # first index row of slice
        while self.SimRes.index[self.i] <= self.t_act: # include elements up to equals t_act AND make sure we don't exceed length of dataframe
            self.i      +=1
            if self.i >= len(self.SimRes) - 1:
                break
        df_slice = self.SimRes.iloc[i_old : self.i , :]

        self.iteration += 1
        return df_slice
    
    def eol(self):
        #determine, if we reached the end of the simulation
        return self.i >= len(self.SimRes) - 1
    
    def reset(self):
        self.i = 0
        self.iteration = -1
        self.t_act      = self.SimRes.index[self.i]