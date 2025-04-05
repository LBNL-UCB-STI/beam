import numpy as np
import numpy.matlib
import matplotlib.pyplot as plt

from spmc_v3 import SPM_Control


Ness = 1    # Number of ESS
dt = 1      # min

ESS_capacity = 50      # in kWh

######################################
### Variables that need to be updated 
### every sanpling time
######################################
# from ESS    
ESS_soc = 0.7              

# from BEAM
Tdep = [70, 150, 120, 200]     # departure time in minute from the current time
Ereq = [20, 20, 25, 40]      # energy remaining for each EV

Pmax = [20, 25, 15, 20]    # max EV charging power (EVSE power rate)
Pmin = [0, 0, 0, 0]        # min EV charging power

# from DERMS
Pmax_site = 30
Pmin_site = 0

######################################

spmc = SPM_Control(time_step_mins=15, num_ess=1, ess_size=ESS_capacity,  
                   max_power_evse=Pmax, min_power_evse=Pmin)

[p_evse_setpoint, p_ess_setpoint, 
 p_evse_opt, e_evse_opt, p_ess_opt, e_ess_opt, delta_t, flag] = spmc.get_evse_setpoint(Tdep, Ereq, Pmin_site, Pmax_site, ESS_soc)

#p_evse_setpoint, p_ess_setpoint = spmc.get_evse_setpoint(ESS_soc, Tdep, Ereq, Pmin_site, Pmax_site)[0:2]
#flag = -1

print('EVSE setpoint: {0}'.format(p_evse_setpoint))
print('ESS setpoint : {0}'.format(p_ess_setpoint))

### ADD a routine in case of flag = -1 (no solution)

if flag > 0:
    #######################################
    ### PLOT
    #######################################
    Tmax = int(np.ceil(max(Tdep)/spmc.time_step_mins))
    Nev = len(Pmax)
    
    Pev_agg = np.zeros(Tmax)     # EV charging profile for plotting
    Eev_agg = np.zeros(Tmax)     # EV energy profile
    P_total = np.zeros(Tmax)
    
    for k in range(Nev):
        Pev_agg = Pev_agg + np.array(p_evse_opt[(k*Tmax):((k+1)*Tmax)])
    
    P_total = Pev_agg + np.array(p_ess_opt)
    
    t = np.linspace(0, max(Tdep), Tmax)
    
    plt.figure()
    fig, axs = plt.subplots(2)
    p_ev1,   = axs[0].plot(t, p_evse_opt[:Tmax])
    p_ev2,   = axs[0].plot(t, p_evse_opt[Tmax:2*Tmax])
    p_ev3,   = axs[0].plot(t, p_evse_opt[2*Tmax:3*Tmax])
    #p_ev4,   = axs[0].plot(t, p_evse_opt[3*Tmax:-1])
    p_ev4,   = axs[0].plot(t, p_evse_opt[3*Tmax:])
    
    e_ev1,   = axs[1].plot(t, e_evse_opt[:Tmax])
    e_ev2,   = axs[1].plot(t, e_evse_opt[Tmax:2*Tmax])
    e_ev3,   = axs[1].plot(t, e_evse_opt[2*Tmax:3*Tmax])
    #e_ev4,   = axs[1].plot(t, e_evse_opt[3*Tmax:-1])
    e_ev4,   = axs[1].plot(t, e_evse_opt[3*Tmax:])
    
    axs[0].legend((p_ev1, p_ev2, p_ev3, p_ev4), ('EV1', 'EV2', 'EV3', 'EV4'), 
                  loc='upper left')
    axs[1].legend((e_ev1, e_ev2, e_ev3, e_ev4), ('EV1', 'EV2', 'EV3', 'EV4'), 
                  loc='upper left')
    
    plt.xlabel('Time')
    axs[0].set(ylabel='Power [kW]')
    axs[1].set(ylabel='Energy [kWh]')
    
    
    plt.figure()
    #plt.plot(t, P_total[:-1])
    plt.plot(t, P_total)
    plt.xlabel('Time (min)')
    plt.ylabel('Power [kW]')
    plt.title('Total Power')
    plt.ylim([Pmin_site, Pmax_site])
    
    plt.figure()
    fig, ax1 = plt.subplots()
    ax2 = ax1.twinx()
    #ax1.plot(t, p_ess_opt[:-1], 'g-')
    ax1.plot(t, p_ess_opt, 'g-')
    ax2.plot(t, np.array(e_ess_opt)/ESS_capacity, 'b-')
    
    ax1.set_xlabel('Time (min)')
    ax1.set_ylabel('ESS Power [kW]', color='g')
    ax2.set_ylabel('ESS SOC', color='b')
    
