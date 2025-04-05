import numpy as np
import logging
class SimBrokerDummy():

    # simple wrapper for time. usage of this object, because historically the Simulation Broker Object reference is given to a lot of modules and functions to provide the correct time.

    def __init__(self,t_start, timestep_intervall) -> None:
        self.t_act = t_start   # in seconds
        self.t_start = t_start   # in seconds
        self.timestep_intervall = timestep_intervall
        
        self.iteration = -1 # first call of step will set timestep to 0 for first simulation iteration.

    def updateTime(self, newTime):
        self.t_act      = newTime
        self.iteration  += 1
        if self.iteration != np.floor((self.t_act - self.t_start)/self.timestep_intervall):
            logging.error("Error in SimBrokerDummy: timestep_intervall is not a divisor of the simulation time. This will lead to errors in the simulation.")

    # TODO add something for iteration