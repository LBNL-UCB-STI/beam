import math
import numpy as np

#=========================================================
#      SPM control algorithm starts here
#      objective: minimize peak power
#=========================================================     
class SPM_Control():
    def __init__(self, time_step_mins, max_power_evse, min_power_evse):
        self.time_step_mins = 1                   # time step: 1 minutes
        
        self.max_power_evse   = max_power_evse
        self.min_power_evse   = min_power_evse
        
        self.time_horizon    = 0      # total time horizon in min
        
        
    def get_evse_setpoint(self, t_dep, energy_req, min_power, max_power):
        # t_dep = [t if t >= 0 else 0 for t in t_dep]
        
        # N = len(t_dep)      # number of plugged EV
        # if N > 0:
        #     self.time_horizon = max(t_dep)
        # else: 
        #     self.time_horizon = 0

        # delta_t = [1]*int(self.time_horizon)
        
        # if len(delta_t) == 0:
        #     p_evse_opt = [min(max(self.max_power_evse), max_power)]*N
        #     e_evse_opt = [k/60 for k in p_evse_opt]
        #     delta_t    = []
        #     return [p_evse_opt, e_evse_opt, delta_t]
  
        # p_evse_opt = np.array([0.0]*len(delta_t)*N)
        # e_evse_opt = np.array([0.0]*len(delta_t)*N)
        
        # p_evse_setpoint = self.get_heuristic_pwr_setpoint(t_dep, energy_req, min_power, max_power)
        p_evse_setpoint = self.max_power_evse
        e_evse_opt = []
        delta_t = []
            
        return [p_evse_setpoint, e_evse_opt, delta_t]


    # def get_heuristic_pwr_setpoint(self, t_dep, energy_req, min_power, max_power):
        
    #     min_pwr_req_evse = np.minimum(np.nan_to_num(np.array(energy_req)/(np.array(t_dep)/60)), np.array(self.max_power_evse))
    #     ev_complete = np.array(t_dep) <= 0
    #     min_pwr_req_evse[ev_complete] = 0
        
    #     total_pwr_req_evse = sum(min_pwr_req_evse)
        
    #     if total_pwr_req_evse >= max_power:
    #         p_evse_setpoint = np.minimum((max_power)*min_pwr_req_evse/total_pwr_req_evse, self.max_power_evse)
            
    #     else:
    #         p_evse_setpoint = min_pwr_req_evse
            
        
    #     return list(p_evse_setpoint)
