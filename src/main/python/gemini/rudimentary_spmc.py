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
        N = len(t_dep)      # number of plugged EV
        self.time_horizon    = max(t_dep)

        delta_t = [1]*self.time_horizon
  
        p_evse_opt = np.array([0.0]*len(delta_t)*N)
        e_evse_opt = np.array([0.0]*len(delta_t)*N)
        
        ev_index   = np.linspace(0, len(delta_t)*(N-1), N).astype(int)
                                       
        for i in range(len(delta_t)):
            p_evse_setpoint = self.get_heuristic_pwr_setpoint(t_dep, energy_req, min_power, max_power, i)
            
            p_evse_opt[ev_index+i] = p_evse_setpoint
                
            if i < len(delta_t)-1:
                e_evse_opt[ev_index+i+1] = e_evse_opt[ev_index+i] + p_evse_opt[ev_index+i]*delta_t[i]/60
                                                   
            t_dep_tmp = np.array(t_dep) - delta_t[i]
            if i < len(delta_t)-1:
                e_req_tmp = np.array(energy_req) - np.array(p_evse_opt[ev_index+i]*delta_t[i]/60)
            
            t_dep_tmp[t_dep_tmp <=0] = 0
            e_req_tmp[t_dep_tmp <= 0] = 0
            t_dep = t_dep_tmp
            energy_req = e_req_tmp
                    
        return [p_evse_opt, e_evse_opt,delta_t]


    def get_heuristic_pwr_setpoint(self, t_dep, energy_req, min_power, max_power, t):
        
        min_pwr_req_evse = np.minimum(np.nan_to_num(np.array(energy_req)/(np.array(t_dep)/60)), np.array(self.max_power_evse))
        ev_complete = np.array(t_dep) <= 0
        min_pwr_req_evse[ev_complete] = 0
        
        total_pwr_req_evse = sum(min_pwr_req_evse)
        
        if total_pwr_req_evse >= max_power:
            p_evse_setpoint = np.minimum((max_power)*min_pwr_req_evse/total_pwr_req_evse, self.max_power_evse)
            
        else:
            p_evse_setpoint = min_pwr_req_evse
            
        
        return p_evse_setpoint
