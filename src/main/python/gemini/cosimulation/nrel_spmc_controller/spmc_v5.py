from gekko import GEKKO
import numpy as np

#=========================================================
#      SPM control algorithm starts here
#      objective: minimize peak power
#=========================================================     
class SPM_Control():
    def __init__(self, max_power_evse, min_power_evse, time_step_mins=None, 
                 num_ess=None, ess_size=None, max_power_ess=None, min_power_ess=None):
        
        if time_step_mins is None:
            self.time_step_mins = 1               # default time step: 1 minutes
        else:
            self.time_step_mins = time_step_mins  
            
        if num_ess is None:
            self.num_ess = 1                      # number of ESS on site  
        else:
            self.num_ess = min(num_ess, 1)
        
        if ess_size is None or self.num_ess == 0:
            self.ess_size   = 0
        else:
            self.ess_size   = ess_size            # in kWh 
            
        if max_power_ess is None: 
            self.max_power_ess = ess_size         # 1C charge
        else:
            self.max_power_ess  = max_power_ess
            
        if min_power_ess is None:
            self.min_power_ess = -ess_size        # 1C discharge
        else:
            self.min_power_ess  = min_power_ess
            
        self.max_energy_ess = 0.99*self.ess_size  # 0.99 maximum SOC
        self.min_energy_ess = 0.20*self.ess_size  # 0.2 minimum SOC
        
        self.max_power_evse   = max_power_evse
        self.min_power_evse   = min_power_evse
        
        self.time_horizon   = 0      # in min
        
        
    def get_evse_setpoint(self, t_dep, energy_req, min_power, max_power, soc_ess, min_power_evse, max_power_evse):
        m = GEKKO(remote=False)
        
        ### tolerance options (default: 1e-6)
        m.options.OTOL = 1e-5
        m.options.RTOL = 1e-5
        
        t_dep = np.maximum(0, t_dep)
        N = t_dep.size      # number of plugged EV
        #self.time_horizon = int(min(np.max(t_dep), 30))
        self.time_horizon = int(np.max(t_dep))
        
        delta_t = np.ones(int(np.ceil(self.time_horizon/self.time_step_mins)))*self.time_step_mins
        T = delta_t.size
        
        ### variables declaration
        p_evse = m.Array(m.Var, T*N)
        e_evse = m.Array(m.Var, T*N)

        if self.num_ess > 0:        
            p_ess  = m.Array(m.Var, T)
            e_ess  = m.Array(m.Var, T)
        
        ### variable initialization
        for i in range(T):
            for k in range(N):
                p_evse[i + k*T] = m.Var(value=min_power_evse[k], lb=min_power_evse[k], 
                                                                      ub=max_power_evse[k])
                e_evse[i + k*T]  = m.Var(value=0, lb=0, ub=energy_req[k])
            
            if self.num_ess > 0:
                p_ess[i]  = m.Var(value=0, lb=self.min_power_ess, ub=self.max_power_ess)
                e_ess[i]  = m.Var(value=0, lb=self.min_energy_ess, ub=self.max_energy_ess)
        
        ### introduction of an additional variable for min max problem
        Z = m.Var()
        
        m.Minimize(Z)
        
        ### Constraints
        ### initial energy for ESS and EVSE
        if self.num_ess > 0:
            m.Equation( soc_ess*self.ess_size == e_ess[0] )
        for k in range(N):
            m.Equation( 0 == e_evse[k*T] )
        
        for i in range(T):
            tmp = 0
            for k in range(N):
                ### no charging after departure
                if i*self.time_step_mins >= t_dep[k]-self.time_step_mins:
                    m.Equation( 0 == p_evse[i + k*T] )
                
                ### summation of all EVSE power at each time step i
                tmp = tmp + p_evse[i + k*T]
                
                ### EVSE energy calculation
                if i > 0:
                    m.Equation( e_evse[i + k*T] == e_evse[i-1 + k*T] + 
                                                   p_evse[i-1 + k*T]*self.time_step_mins/60 )
                
                ### should deliver requested energy by departure
                if i*self.time_step_mins >= t_dep[k]-self.time_step_mins:
                    m.Equation( energy_req[k] <= e_evse[i + k*T] )
            
            ### ESS energy calculation
            if self.num_ess > 0:
                if i > 0:
                    m.Equation( e_ess[i] == e_ess[i-1] + p_ess[i-1]*self.time_step_mins/60 )
            
            ### site net power limits
            #m.Equation( min_power <= Z)
            if self.num_ess > 0:
                m.Equation( min_power <= tmp + p_ess[i])

            m.Equation( max_power >= Z)
            
            ### min-max
            if self.num_ess > 0:
                m.Equation( Z >= tmp + p_ess[i])
            else:
                m.Equation( Z >= tmp)


        try:
            m.solve(disp=False)
            print('Z: {0}'.format(Z.value[0]))
            flag = 1
            
            if self.num_ess > 0:
                p_ess_opt  = [ p_ess[i].value[0] for i in range(T) ]
                e_ess_opt  = [ e_ess[i].value[0] for i in range(T) ]
                p_ess_setpoint = p_ess[0].value[0]
            else:
                p_ess_opt = [0]*T
                e_ess_opt = [0]*T
                p_ess_setpoint = [0]
        
            p_evse_opt = [ p_evse[i].value[0] for i in range(T*N) ]
            e_evse_opt = [ e_evse[i].value[0] for i in range(T*N) ]
        
            p_evse_setpoint = [0]*N
            for k in range(N):
                p_evse_setpoint[k] = p_evse_opt[k*T]
            
        except:
            print('No solution found.')
            flag = -1
            
            [p_evse_setpoint, p_ess_setpoint] = self.get_heuristic_evse_setpoint(t_dep, energy_req, min_power, max_power, soc_ess, min_power_evse, max_power_evse)
            
            p_evse_opt = []
            e_evse_opt = []
            p_ess_opt = []
            e_ess_opt = []
                  
        return [p_evse_setpoint, p_ess_setpoint, p_evse_opt, e_evse_opt, p_ess_opt, e_ess_opt, delta_t, flag]
    
    def get_heuristic_evse_setpoint(self, t_dep, energy_req, min_power, max_power, soc_ess, min_power_evse, max_power_evse):
        min_pwr_req_evse = np.minimum(np.nan_to_num(np.array(energy_req)/(np.array(t_dep)/60)), np.array(max_power_evse))
        ev_complete = np.array(t_dep) <= 0
        min_pwr_req_evse[ev_complete] = 0
        
        total_min_pwr_req_evse = min_pwr_req_evse.sum()
        p_evse_setpoint = np.minimum((max_power)*min_pwr_req_evse/total_min_pwr_req_evse, max_power_evse)
        total_max_pwr_req_evse = p_evse_setpoint.sum()
        
        ess_energy_available = soc_ess*self.ess_size
        
        p_ess_setpoint = np.maximum(-ess_energy_available, self.min_power_ess)
        p_evse_setpoint = np.minimum((max_power-p_ess_setpoint)*min_pwr_req_evse/total_min_pwr_req_evse, max_power_evse)
        
        
        return [p_evse_setpoint, p_ess_setpoint]

