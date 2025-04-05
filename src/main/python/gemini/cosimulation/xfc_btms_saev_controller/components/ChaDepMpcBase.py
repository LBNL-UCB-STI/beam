from multiprocessing.sharedctypes import Value
import pandas as pd
import os
from typing import List
import components
from components import ChaDepParent
import numpy as np
import cvxpy as cp
import cvxpy.atoms.max as cpmax
import time as time_module
import logging
import matplotlib.pyplot as plt

class ChaDepMpcBase(ChaDepParent):
    '''see mpcBase.md for explanations'''

    def __init__(self, ChargingStationId, ResultWriter: components.ResultWriter, SimBroker: components.SimBroker, ChBaMaxPower, ChBaParkingZoneId, ChBaNum, BtmsSize=100, BtmsC=1, BtmsMaxSoc=1.0, BtmsMinSOC=0.0, BtmsSoc0=0.5, calcBtmsGridProp=False, GridPowerMax_Nom=1, GridPowerLower=-1, GridPowerUpper=1):

        super().__init__(ChargingStationId, ResultWriter, SimBroker, ChBaMaxPower, ChBaParkingZoneId, ChBaNum, BtmsSize, BtmsC, BtmsMaxSoc, BtmsMinSOC, BtmsSoc0, calcBtmsGridProp, GridPowerMax_Nom, GridPowerLower, GridPowerUpper)

        '''additional parameters for results of btms size optimization'''
        self.determinedBtmsSize     = None
        self.determinedMaxPower     = None

        '''additional variables for MPC:'''
        #variables for storing data
        self.PredictionTime         = []    # time vector
        self.PredictionPower        = []    # predicted, unconstrained power
        self.PredictionTimeLag      = []    # associated time lag vector
        self.PredictionEnergyLag    = []    # associated energy lag vector
        self.power_sum_original     = []    # predicted, unconstrained power, with no noise applied
        self.PredictionGridUpper    = []    # TODO used so far?
        self.PredictionGridLower    = []    # TODO used so far?
        self.E_BtmsLower            = []    # btms energy from planning
        self.E_BtmsUpper            = []    # btms energy from planning

        self.OptimalValues          = None  # optimal values of MPC solver
        self.VectorT1               = None  # vector t1, which is the feasibility guaranteeing variable for the vehicle charge trajectory
        self.VectorT2               = None  # vector t2, which is the feasibility guaranteeing variable for the energy conservation, in case the BTMS is fully empty.

        # variables
        self.P_GridLast             = None      # last Grid Power, used to flatten the MPC power curve
        self.P_GridMaxPlanning      = None      # maximal P_Grid from planning, used to keep demand charge low
        self.P_ChargeGranted        = 0         # power granted by MPC to vehicles
        self.P_ChargeDelivered      = 0         # power delivered to vehicles
        self.P_BTMSGranted          = 0         # power granted by MPC to BTMS
        self.P_BTMSDeliverable      = 0         # power deliverable by BTMS
        self.N                      = 4      # number of short horizoned steps in MPC



    def generatePredictions(self, path_BeamPredictionFile, dtype, path_DataBase, timestep=5*60, addNoise = True):
        # generate a prediction for the charging station
        # neglection of charging desire, make this not too good
        ChBaVehicles = []
        Queue = []
        #open a SimBroker object for this
        PredBroker = components.SimBroker(path_BeamPredictionFile, dtype)
        # open a VehicleGenerator for this:
        VehicleGenerator = components.VehicleGenerator(path_BeamPredictionFile, dtype, path_DataBase)
        
        # open lists for power and time
        time = []
        power_sum = []
        # calculate also time lag and energy lag as reference values
        time_lag = []
        energy_lag = []

        #add all vehicle to queue which arrive at this charging station
        while not PredBroker.eol():
            slice = PredBroker.step(timestep)
            for i in range(0, len(slice)):
                if slice.iloc[i]["type"] == "ChargingPlugInEvent":
                    vehicle = VehicleGenerator.generateVehicleSO(slice.iloc[i])
                    if np.isin(element=self.ChBaParkingZoneId, test_elements=vehicle.BeamDesignatedParkingZoneId).any():
                        Queue.append(vehicle)
            # add vehicle to charging bays if possible
            while len(ChBaVehicles) < self.ChBaNum and len(Queue) > 0:
                ChBaVehicles.append(Queue.pop(0))
            # charge vehicles with maximum possible power
            power_i = []
            for x in ChBaVehicles:
                p = min([x.getMaxChargingPower(timestep), self.ChBaMaxPower_abs])
                x.addPower(p, timestep)
                power_i.append(p)
            #save result in vectors
            time.append(PredBroker.t_act)
            power_sum.append(sum(power_i))
            # calculate time lag and energy lag
            time_lag.append(sum([x.updateTimeLag(PredBroker.t_act) for x in ChBaVehicles]))
            energy_lag.append(sum([x.updateEnergyLag(PredBroker.t_act) for x in ChBaVehicles]))
            #release vehicles which are full
            pop_out = []
            for i in range(0,len(ChBaVehicles)):
                if ChBaVehicles[i].VehicleEngy >= ChBaVehicles[i].VehicleDesEngy:
                    pop_out.append(i)
            for i in range(0, len(pop_out)):
                x = ChBaVehicles.pop(pop_out[i]-i) # -i makes up for the loss of list-length after pop 
                # print(x.VehicleSoc)
                # print(x.VehicleDesEngy/x.VehicleMaxEngy)
        
        # add noise to produce prediction:
        self.power_sum_original = power_sum.copy()
        if addNoise:
            param = 0.4
            avg = np.average(power_sum)
            # seed random variable
            np.random.seed(1)
            for i in range(0,len(power_sum)):
                 power_sum[i] = power_sum[i] + avg * (np.random.randn() * param)
                 if power_sum[i] < 0:
                     power_sum[i] = 0
        # save to Prediction Variables
        self.PredictionTime = time
        self.PredictionPower = power_sum
        self.PredictionTimeLag = time_lag
        self.PredictionEnergyLag = energy_lag

        # generate a prediction for power limits
        # TODO: so far no implemented deviations
        for i in time:
            self.PredictionGridUpper.append(self.GridPowerMax_Nom)
            self.PredictionGridLower.append(- self.GridPowerMax_Nom)
        
        #save results to csv-file
        dict = {
            'time': time,
            'Power_original': self.power_sum_original,
            'Power_noise': self.PredictionPower,
            'TimeLag': self.PredictionTimeLag,
            'EnergyLag': self.PredictionEnergyLag,
            'PredictionGridUpper': self.PredictionGridUpper,
            'PredictionGridLower': self.PredictionGridLower,
        }
        df = pd.DataFrame({ key:pd.Series(value) for key, value in dict.items() })
        dir         = os.path.join(self.ResultWriter.directory,'generatePredictions')
        os.makedirs(dir, exist_ok=True) 
        filename    = self.ChargingStationId + ".csv"
        df.to_csv(os.path.join(dir, filename))

    def plotPrediction(self, directory):
        time = self.PredictionTime
        power = self.PredictionPower

        ax = plt.subplot()
        ax.plot(time,power, label = 'with noise')
        ax.plot(time,self.power_sum_original, label = 'without noise')
        ax.legend()
        plt.savefig(os.path.join(directory, self.ChargingStationId + '_prediction.png'))
        # return fig
        return ax
        
    def determineBtmsSize(self, t_act, t_max, timestep, a, b, c, P_free):
        '''see mpcBase.md for explanations'''
        # vector lengthes
        T = int(np.ceil((t_max - t_act) / timestep))

        # define variables 
        x = cp.Variable((1, T+1))
        u = cp.Variable((4, T))
        p_gridSlack = cp.Variable((1,1)) # slack variable to determine demand charge with free demand charge level, e.g. if p_max > 20kW, demand charge applied
        
        # define disturbance i_power, which is the charging power demand
        time = np.array(self.PredictionTime)
        power = np.array(self.PredictionPower)
        idx = np.logical_and(time >=t_act, time <= t_act + T*timestep)
        time = time[idx]
        i_power = power[idx]
        if len(i_power) != T:
            logging.warning("length of i_power does not match T, length of i_power: " + str(len(i_power)) + ", T: " + str(T))
            raise ValueError("length T and length of vector i_power are unequal, i_power: " + str(len(i_power)) + ", T: " + str(T))

        #parameters
        ts = timestep / 3.6e3
        eta = self.BtmsEfficiency

        constr = []
        # define constraints
        for k in range(T):
            constr += [x[:,k+1] == x[:,k] + ts * eta * u[2,k] + ts * 1/eta * u[3,k], # btms charging equation
                        u[0,k] - u[1,k] == i_power[k], # energy flow equation
                        u[1,k] == u[2,k] + u[3,k], # P_BTMS is sum of charge and discharge
                        u[2,k] >= 0, # charging power always positive
                        u[3,k] <= 0, # discharge power always negative
                        ]
        # insert initial constraint, bound BTMS size and define free power level
        constr +=  [x[:,0]== 0,
                    x[:,0] == x[:,T],
                    p_gridSlack >= cpmax(u[0,:]),
                    p_gridSlack >= P_free,]
        
        # define cost-funciton
        cost = a * (p_gridSlack - P_free) # demand charge
        for k in range(T):       # cost of btms degradation and cost of energy loss
            cost += (b+c) * u[2,k] * ts + c * u[3,k] * ts # u[3,k] is always negative

        # solve the problem
        logging.info("\n----- \n btms size optimization for charging station %s \n-----" % self.ChargingStationId)
        prob = cp.Problem(cp.Minimize(cost), constr)
        components.solverAlgorithm(prob)
        
        # determine BTMS size and unpack over values
        btms_size = np.max(x.value) - np.min(x.value)
        P_Grid = u[0,:].value
        P_BTMS = u[1,:].value
        E_BTMS = x[0,:].value
        P_Charge = i_power
        P_BTMS_Ch = u[2,:].value
        P_BTMS_DCh = u[3,:].value
        cost = prob.value
        time_x = time.tolist()
        time_x.append(time[-1]+timestep)
        time_x = np.array(time_x) # time_x is the time vector for states, time the time vector for control inputs

        self.determinedBtmsSize = btms_size
        self.BtmsSize = btms_size
        self.determinedMaxPower = max(abs(P_BTMS))

        #save results to csv-file
        param_vec = np.zeros_like(time)
        param_vec = param_vec.tolist()
        param_vec[0] = self.determinedBtmsSize
        param_vec[1] = a
        param_vec[2] = b
        param_vec[3] = c
        dict = {
            'time': time,
            'time_x': time_x,
            'P_Grid': P_Grid,
            'P_BTMS': P_BTMS,
            'E_BTMS': E_BTMS[:-1],
            'P_Charge': P_Charge,
            'P_BTMS_Ch': P_BTMS_Ch,
            'P_BTMS_DCh': P_BTMS_DCh,
            'param: btms size, a,b,c': param_vec,
        }
        df = pd.DataFrame({ key:pd.Series(value) for key, value in dict.items() })
        dir         = os.path.join(self.ResultWriter.directory,'determineBtmsSize')
        os.makedirs(dir, exist_ok=True) 
        filename    = self.ChargingStationId + ".csv"
        df.to_csv(os.path.join(dir, filename))

        return time, time_x, btms_size, P_Grid, P_BTMS, P_BTMS_Ch, P_BTMS_DCh, E_BTMS, P_Charge, cost

    def planning(self, t_act, t_max, timestep, a, b, c, d_param, P_free, P_ChargeAvg, beta, cRating=None, verbose=True):
        time_start = time_module.time() # timing
        '''see mpcBase.md for explanations'''
        # vector length T
        T = int(np.ceil((t_max - t_act) / timestep))

        # define variables 
        x = cp.Variable((2, T+1))
        u = cp.Variable((5, T))
        p_gridSlack = cp.Variable((1,1))    # slack variable to determine demand charge with free demand charge level, e.g. if p_max > 20kW, demand charge applied
        t_wait = cp.Variable((1,T))
        n = cp.Variable((2,T))
        
        # define disturbance i_power for the needed time period, which is the charging power demand
        time = np.array(self.PredictionTime)
        power = np.array(self.PredictionPower)
        idx = np.logical_and(time >=t_act, time <= t_act + T*timestep)
        time = time[idx]
        i_power = power[idx]
        if len(i_power) != T:
            logging.warning("length of i_power is not equal to T, i_power: %s, T: %s" % (len(i_power), T))
            raise ValueError("length T and length of vector i_power are unequal, T: %s, i_power: %s" % (T, len(i_power)))

        #create array for cost-function parameter d, if wait time cost is not flexible (given as an array)
        if type(d_param) != list:
            d = []
            for i in range(len(i_power)+1):
                d.append(d_param)
        else:
            d = d_param
            if len(d) != T:
                logging.warning("length of d is not equal to T, d: %s, T: %s" % (len(d), T))
                raise ValueError("length T and length of vector d are unequal, d: %s, T: %s" % (len(d), T))

        #parameters
        ts = timestep / 3.6e3
        eta = self.BtmsEfficiency

        # define constraints (including system dynamics)
        constr = []
        for k in range(T):
            constr += [
                        x[0, k+1] == x[0, k] + ts * eta * u[2, k] + ts * 1/eta * u[3, k],    # BTMS equation
                       # shifted energy equation
                       x[1, k+1] == x[1, k] + ts * u[4, k],
                       # energy flow equation
                       u[0, k] - u[1, k] == i_power[k] - u[4, k],
                       # P_BTMS is sum of charge and discharge
                       u[1, k] == u[2, k] + u[3, k],
                       # charging power always positive
                       u[2, k] >= 0,
                       # discharge power always negative
                       u[3, k] <= 0,
                       # wait time
                       t_wait[0, k] >= ts * (n[0, k] + n[1, k]),
                       # wait time due to already shifted energy
                       n[0, k] >= x[1, k]/(P_ChargeAvg * ts),
                       # wait time due to newly shifted energy
                       n[1, k] >= u[4, k] / P_ChargeAvg,
                       # wait time due to newly shifted energy is always positive
                       n[1, k] >= 0,
                       ]

        # btms power limits
        if cRating != None:
            for k in range(T):
                constr += [u[2,k] <= cRating*self.BtmsSize,     # upper power limit,
                            u[3,k] >= -cRating*self.BtmsSize,   # discharge power always negative
                            ]

        # btms size limits
        for k in range(T+1):
            constr += [x[0,k] >= 0,                 # lower limit of BTMS size
                        x[0,k] <= self.BtmsSize,    # upper limit of BTMS size
                        x[1,k] >= 0,                # shifted energy is only a positive bin
                        ]

        # insert initial constraint, bound BTMS charge/discharge and define free power level
        constr +=  [x[0,0] == x[0,T],               # ensure not to discharge BTMS to minimize cost function
                    x[1,0] == 0,                    # set shifted Energy at beginning to zero
                    x[1,T] == 0,                    # shifted energy should be zero at end
                    p_gridSlack >= cpmax(u[0,:]),
                    p_gridSlack >= P_free,
                    ]
        
        # define cost-funciton
        cost = a * (p_gridSlack - P_free)           # demand charge
        for k in range(T):                          # cost of btms degradation, cost of energy loss, cost of waiting time
            cost += (b+c) * u[2,k] * ts + c * u[3,k] * ts + d[k] * t_wait[0,k]

        time_end1 = time_module.time() # timewatch

        # solve the problem
        logging.info("\n----- \n day planning for charging station %s \n-----" % self.ChargingStationId)
        prob = cp.Problem(cp.Minimize(cost), constr)
        components.solverAlgorithm(prob)

        time_end2=time_module.time()    # timewatch

        # logging additional solver stats
        logging.info("self tracked times: setup time: %s, solve time: %s, total action time: %s" % (time_end1-time_start, time_end2-time_end1, time_end2-time_start))

        # unpack results
        P_Grid = u[0,:].value
        P_BTMS = u[1,:].value
        E_BTMS = x[0,:].value
        E_Shift = x[1,:].value
        P_Charge = i_power
        P_Shift = u[4,:].value
        P_BTMS_Ch = u[2,:].value
        P_BTMS_DCh = u[3,:].value
        t_wait_val = t_wait[0,:].value
        cost_t_wait = 0
        for k in range(len(t_wait_val)):
            cost_t_wait += d[k] * t_wait_val[k]
        cost = prob.value
        time_x = time.tolist()
        time_x.append(time[-1]+timestep)
        time_x = np.array(time_x)       # time_x is the time vector for states, time the time vector for control inputs, time_x is one entry longer

        # save important values to object
        self.P_GridMaxPlanning = max(P_Grid)
        self.E_BtmsLower        = []
        self.E_BtmsUpper        = []
        for repeat in range(2): # double the E_BTMSLower and Upper vector length to have sufficient long prediction vectors for the last time steps.
            for i in range(T+1):
                self.E_BtmsLower.append(max([0            , E_BTMS[i] - beta * self.BtmsSize]))
                self.E_BtmsUpper.append(min([self.BtmsSize, E_BTMS[i] + beta * self.BtmsSize]))
        
        # initialize charging station with planning results
        self.P_Grid = P_Grid[0]   # grid power last with first planning value
        self.BtmsEn = E_BTMS[0]       # BTMS energy with first planning value
        
        #save results to csv-file
        param_vec = np.zeros_like(time)
        param_vec[0] = self.BtmsSize
        param_vec[1] = a
        param_vec[2] = b
        param_vec[3] = c
        dict = {
            'time': time,
            'time_x': time_x,
            'P_Grid': P_Grid,
            'P_BTMS': P_BTMS,
            'E_BTMS': E_BTMS,
            'E_BTMS_lower': self.E_BtmsLower,
            'E_BTMS_upper': self.E_BtmsUpper,
            'E_Shift': E_Shift,
            'P_Charge': P_Charge,
            'P_Shift': P_Shift,
            'P_BTMS_Ch': P_BTMS_Ch,
            'P_BTMS_DCh': P_BTMS_DCh,
            't_wait': t_wait_val,
            'param: btms size, a,b,c': param_vec,
            'd': d
        }
        df = pd.DataFrame({ key:pd.Series(value) for key, value in dict.items() })
        dir         = os.path.join(self.ResultWriter.directory,'planning')
        os.makedirs(dir, exist_ok=True) 
        filename    = self.ChargingStationId + ".csv"
        df.to_csv(os.path.join(dir, filename))
        logging.info("planning results saved to %s" % os.path.join(dir, filename))

        return time, time_x, P_Grid, P_BTMS, P_BTMS_Ch, P_BTMS_DCh, E_BTMS, E_Shift, P_Charge, P_Shift, t_wait_val, cost_t_wait, cost

    def runMpc(self, timestep, N, SimBroker: components.SimBroker, M1 = 100, M2 = 200, verbose = False):
        # N is the MPC optimization horizon

        # obtain inputs
        P_GridLast          = cp.Parameter(value=self.P_GridLast)
        i_act               = SimBroker.iteration         # actual iteration to read out from planning results
        P_GridMaxPlanning   = self.P_GridMaxPlanning  
        P_GridMaxPlanning   = cp.Parameter(value=P_GridMaxPlanning)   
        P_GridUpper         = self.GridPowerUpper 
        P_GridUpper         = cp.Parameter(value=P_GridUpper)
        btms_size           = self.BtmsSize
        # this is a bit harder to implement for parameters
        E_BtmsLower         = self.E_BtmsLower
        E_BtmsUpper         = self.E_BtmsUpper
        E_Btms              = self.BtmsEn
        # charging trajectories from cars
        E_V_lower = np.zeros(N+1)
        E_V_upper = np.zeros(N+1)
        for x in self.ChBaVehicles:
            if x != False:
                lower, upper = x.getChargingTrajectories(SimBroker.t_act, timestep, N)
                E_V_upper = np.add(E_V_upper, upper)
                E_V_lower = np.add(E_V_lower, lower)

        #parameters
        ts = timestep / 3.6e3
        eta = self.BtmsEfficiency

        # set up control problem

        # define variables 
        x = cp.Variable((2, N+1))
        u = cp.Variable((5, N))
        t1 = cp.Variable((1,N)) # this goes from [0, N]: for control output variable
        t2 = cp.Variable((1,N)) # this goes from [1, N+1]: for state variable, first (0) is already bound
        P_avg = cp.Variable((1,1))

        constr = []

        for k in range(N):
            # system dynamics and control variable constraints
            constr += [
                        x[0, k+1] == x[0, k]  + ts * u[1, k], #+ ts * (eta * u[2,k] + 1/eta * u[3, k]),   # system dynamic btms 
                        x[1, k+1] == x[1, k] + ts * u[4, k],    # system dynamic charged energy to vehicles
                        u[4, k] == u[0, k] - u[1, k],   # energy flow at station
                        #u[1, k] == u[2, k] + u[3, k],   # energy equation charge/discharge
                        u[0, k] <= P_GridMaxPlanning + t2[0, k],  # grid power smaller than value from planning
                        u[0, k] <= P_GridUpper, # upper bound from derms
                        u[2, k] >= 0, 
                        u[3, k] <= 0,
                        u[4, k] >= 0,
                        t1[0, k] >= 0,
                        t2[0, k] >= 0,
            ]
        for k in range(N+1):
            # state constraints
            constr += [
                        x[0, k] >= 0,
                        x[0, k] <= btms_size,
            ]
        # define these constraints from 1 on to reduce the number of redundant constraints
        for k in range(1, N+1):
            constr += [
                        x[0, k] >= E_BtmsLower[i_act + k], # correct position in planning results is i_act + k
                        x[0, k] <= E_BtmsUpper[i_act + k],
                        x[1, k] >= E_V_lower[k]  - t1[0, k-1], # we defined t1 as a vector of length N+1-1, so we need to subtract 1 to get the correct index
                        x[1, k] <= E_V_upper[k],            
            ]
        # initial constraints
        constr += [
            x[0, 0] == E_Btms,
            x[1, 0] == 0,
        ]
        # average power constraint
        constr += [
            P_avg == (cp.sum(u[0, :]) + P_GridLast)/ (N+1),
        ]

        # objective function
        #cost = cp.square(P_GridLast - P_avg)
        cost = 0
        #cost += cp.sum(cp.square(u[0, :] - P_avg))
        for i in range(N):
            #cost += cp.square(u[0, i] - P_avg)
            cost += cp.square(x[0, i] - (E_BtmsLower[i_act + i] + E_BtmsUpper[i_act + i])/2)
        cost += M1 * cp.sum(cp.square(t1[0, :] ))
        cost += M2 * cp.sum(cp.square(t2[0, :] ))

        # solve control problem and print outputs
        prob = cp.Problem(cp.Minimize(cost), constr)
        components.solverAlgorithm(prob)
        logging.info("vector t1: %s |--| vector t2: %s" % (t1.value, t2.value))
        
        #TODO add routine to deal with infeasibilty 
            
        # return control action
        '''our control outputs are P_BTMS  and P_ChargeGranted, P_Grid is the sum of both'''
        P_BTMS = u[1, 0].value
        P_ChargeGranted = u[4, 0].value

        # note optimal value and slack variables
        self.OptimalValues          = prob.value
        self.VectorT1               = t1.value
        self.VectorT2               = t2.value
        self.ResultWriter.updateMpcStats(SimBroker.t_act, self, prob, t1, t2)

        return P_BTMS, P_ChargeGranted

    def step(self, timestep, verbose = False):
        
        '''repark vehicles based on their charging desire with the parent method'''
        self.repark()
        logging.info("Vehicles reparked in %s" % self.ChargingStationId)
        
        ''' get control action'''
        # run MPC to obtain max power for charging vehicles and charging power for BTMS
        self.P_GridLast = self.P_Grid
        self.P_BTMSGranted, P_Charge_Granted = self.runMpc(timestep, self.N, self.SimBroker, verbose = verbose)

        # distribute MPC powers to vehicles (by charging desire)
        self.P_ChargeDelivered = self.distributeChargingPowerToVehicles(timestep, P_Charge_Granted)
        logging.info('P_ChargeGranted: {:.2f}, P_ChargeDelivered: {:.2f}'.format(P_Charge_Granted, self.P_ChargeDelivered))

        # calculate dispatachable BTMS power (checking that bounds aren't exceeded)
        self.P_BTMSDeliverable = self.BtmsGetPowerDeliverable(self.P_BTMSGranted, timestep)
        self.P_BTMS = self.P_BTMSDeliverable
        logging.info('P_BTMS granted: {:.2f}, P_BTMS deliverable: {:.2f}'.format(self.P_BTMSGranted, self.P_BTMSDeliverable))

        # calculate grid power from delivered charge and BTMS power
        self.P_Grid = self.P_ChargeDelivered + self.P_BTMS
        logging.info('P_Grid: {:.2f}'.format(self.P_Grid))

        '''Write chargingStation states for k in ResultWriter'''
        self.ResultWriter.updateChargingStationState(
            self.SimBroker.t_act, self)
        logging.debug("results written for charging station {}".format(self.ChargingStationId))

        '''# update BTMS state for k+1'''
        # BTMS
        self.BtmsAddPower(self.P_BTMS, timestep)
        logging.debug("BTMS state updated for charging station {}".format(self.ChargingStationId))

        '''write vehicle states for k in ResultWriter and update vehicle states for k+1'''
        # Vehicles
        self.updateVehicleStatesAndWriteStates(self.ChBaPower, timestep)
        logging.debug("vehicle states updated for charging station {}".format(self.ChargingStationId))

        '''determine power desire for next time step'''
        PowerDesire = 0
        for i in range(0, len(self.ChBaVehicles)):
            if isinstance(self.ChBaVehicles[i], components.Vehicle):
                PowerDesire += min(
                    [self.ChBaVehicles[i].getMaxChargingPower(timestep), self.ChBaMaxPower[i]])
        self.PowerDesire = PowerDesire
        self.BtmsPowerDesire = self.getBtmsMaxPower(timestep) # TODO this should be changed for DERMS to MPC output
        logging.debug("power desires updated for charging station {}".format(self.ChargingStationId))

        '''release vehicles when fully charged, but this is already at the next timestep'''
        self.resetOutput() # here we reset the control output
        r1 = self.chBaReleaseThresholdAndOutput()
        r2 = self.queueReleaseThresholdAndOutput()
        logging.debug("vehicles released for charging station {}".format(self.ChargingStationId))
        released_Vehicles = r1 + r2
        
        # write vehicle states before releasing them (to have final SOC)
        for x in r1:
            possiblePower = x.getMaxChargingPower(timestep)
            self.ResultWriter.updateVehicleStates(
                    t_act=self.SimBroker.t_act + timestep, vehicle=x, ChargingStationId=self.ChargingStationId, QueueOrBay=False, ChargingPower=0, possiblePower=possiblePower)
        for x in r2:
            possiblePower = x.getMaxChargingPower(timestep)
            self.ResultWriter.updateVehicleStates(
                    t_act=self.SimBroker.t_act + timestep, vehicle=x, ChargingStationId=self.ChargingStationId, QueueOrBay=True, ChargingPower=0, possiblePower=possiblePower)

        # add release events
        for x in released_Vehicles:
            self.ResultWriter.releaseEvent(
                self.SimBroker.t_act + timestep, x, self.ChargingStationId)
        logging.debug("vehicle release events written for charging station {}".format(self.ChargingStationId))

        '''checks'''
        if len(self.ChBaVehicles) != self.ChBaNum:
            raise ValueError("Size of ChargingBay List shouldn't change")
