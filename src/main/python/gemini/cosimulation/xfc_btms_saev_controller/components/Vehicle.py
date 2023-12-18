import numpy as np

class Vehicle:
    def __init__(self, VehicleId, VehicleType, VehicleArrival, VehicleDesEnd, VehicleEngy, VehicleDesEngy, VehicleMaxEngy, VehicleMaxPower, ParkingZoneId = False): # the first inputs are from Beam, the last from the vehicle file
        self.VehicleId      = VehicleId                     # vehicle id
        self.VehicleType    = VehicleType                   # vehicle type
        self.VehicleArrival = VehicleArrival                # arrival time of vehicles
        self.VehicleDesEnd  = VehicleDesEnd                 # desired end times of charging
        self.VehicleEngy    = VehicleEngy                   # Energy State of vehicles [kWh]
        self.VehicleEngy_Arrival = VehicleEngy              # Energy at Arrival, needed for calculation of Energy Lag
        self.VehicleDesEngy = VehicleDesEngy                # desired Energy State of vehicles: level + fuel [kWh]
        self.VehicleSoc     = VehicleEngy / VehicleMaxEngy  # SOC of vehicles [-]
        self.VehicleMaxEngy = VehicleMaxEngy                # maximal energy state of vehicles [kWh]
        self.VehicleMaxPower= VehicleMaxPower               # maximal charging power of vehicles
        self.ChargingDesire = 0                             # charging desire of vehicle, assigned during control steps
        self.EnergyLag      = 0                             # energy lag (rating metric)
        self.TimeLag        = 0                             # time lag (rating metric)
        if not ParkingZoneId == False:
            self.BeamDesignatedParkingZoneId = ParkingZoneId    # ParkingZoneId, used in prediction generation of MPC controller.
    
    def __str__(self):
        # print method
        return ("Vehicle with the following properties: \nVehicleId: " + str(self.VehicleId) + " VehicleType: " + str(self.VehicleType) + " Arrival: " + str(self.VehicleArrival) + " Desired End Time: " + str(self.VehicleDesEnd) + " Vehicle Energy: " + str(self.VehicleEngy) + " Desired Energy: " + str(self.VehicleDesEngy) + " SOC: " + str(self.VehicleSoc) + " Maximal Energy: " + str(self.VehicleMaxEngy) + " Max Charging Power: "  + str(self.VehicleMaxPower) + " Charging Desire: " + str(self.ChargingDesire) + " Energy Lag: " + str(self.EnergyLag) + " Time Lag: " + str(self.TimeLag))

    def getMaxChargingPower(self, timestep, inverse = False):

        if not inverse:
            maxPowerVehicle = self.VehicleMaxPower
            maxPowerForDesEngy = max([0, (self.VehicleDesEngy - self.VehicleEngy)/(timestep/3.6e3)]) # maximum power should be no less than 0
            power = min([maxPowerVehicle, maxPowerForDesEngy]) # the smaller value is the max power.
        elif inverse:
            maxPowerVehicle = self.VehicleMaxPower
            power = min([self.VehicleMaxPower, abs((self.VehicleEngy - self.VehicleEngy_Arrival)/(timestep/3.6e3))])
        return power
    
    def addEngy(self, addedEngy):
        #addedEngy in kWh
        self.VehicleEngy    = self.VehicleEngy + addedEngy  # add energy 
        self.VehicleSoc     = self.VehicleEngy / self. VehicleMaxEngy # update SOC
        self.SOC_warning()

    def addPower(self, power, ts):
        # power in kW, ts in hours
        self.VehicleEngy    = self.VehicleEngy + ts * power/3.6e3 # add energy 
        self.VehicleSoc     = self.VehicleEngy / self. VehicleMaxEngy # update SOC
        self.SOC_warning()
        self.power_warning(power)

    def SOC_warning(self):
        if self.VehicleEngy > self.VehicleMaxEngy:
            print("Warning: Vehicle " + str(self.VehicleId) + " exceeds Vehicle Max SOC. Vehicle Energy " + str(self.VehicleEngy) + ", Vehicle Max Energy " + str(self.VehicleMaxEngy))
    
    def power_warning(self, power):
        if power > self.VehicleMaxPower:
            print("Warning: Vehicle " + self.VehicleId + " exceeds maximal charging power. Charging power " + str(power))

    def updateEnergyLag(self, t_act):
        if self.VehicleDesEnd > t_act:
            E_reference = self.VehicleEngy_Arrival + (self.VehicleDesEngy-self.VehicleEngy_Arrival)/(self.VehicleDesEnd - self.VehicleArrival) * (t_act - self.VehicleArrival)
        else:
            E_reference = self.VehicleDesEngy
        self.EnergyLag = self.VehicleEngy - E_reference
        return self.EnergyLag

    def updateTimeLag(self, t_act): 
        if self.VehicleDesEnd < t_act:
            self.TimeLag = 0
        else:
            self.TimeLag = t_act - self.VehicleDesEnd
        return self.TimeLag

    def copy(self):
        # a copy method of the vehicle, to obtain charging trajectories.
        v = Vehicle(self.VehicleId, self.VehicleType, self.VehicleArrival, self.VehicleDesEnd, self.VehicleEngy, self.VehicleDesEngy, self.VehicleMaxEngy, self.VehicleMaxPower)
        v.VehicleEngy_Arrival = self.VehicleEngy_Arrival # need to correct this because when creating the copy the current energy levele is different to the levele at arrival.
        return v

    def getChargingTrajectoryUpper(self,t_act, timestep, N):
        # the prediction horizon is N, but we need N+1 values for stocks (inital value + N steps)

        # determine upper bound of vehicle energy level charging trajectory. Normalized to energy power level at time 0
        v = self.copy()
        traj = [0.0]
        E0 = v.VehicleEngy
        for i in range(N):
            maxPower = v.getMaxChargingPower(timestep)
            v.addPower(maxPower, timestep)
            traj.append(v.VehicleEngy - E0)
        del v
        return traj

    def getChargingTrajectories(self,t_act, timestep, N):
        # this gives back both lower and upper trajectory, as we need the upper trajectory to check the lower. 
        # trajectories are normalized to energy level at start of charging trajectory
        v = self.copy()
        
        # set energy level to desired energy
        v.VehicleEngy_Arrival = v.VehicleEngy
        v.VehicleEngy = v.VehicleDesEngy

        # if we are already over the desired end time, we just have to charge with maximal power
        if t_act >= v.VehicleDesEnd:
            traj_lower = self.getChargingTrajectoryUpper(t_act, timestep, N)
            traj_upper = traj_lower.copy()
        else:
            traj_lower = [] #trajectory is inversed if we go back in time
            k = int(np.ceil((self.VehicleDesEnd - t_act)/timestep)) # number of timestep from end to charging to beginning, must go back this steps

            if k < N: # if prediction horizon is larger then desired charging duration
                for i in range(N-k): # fill up N-k with Vehicle desired energy, this is until charging ends -1, because the last energy level when charging ends is appended in the loop below
                    traj_lower.append(v.VehicleDesEngy)
                for i in range(k): # start "de-charging" at k
                    traj_lower.append(v.VehicleEngy)
                    power = v.getMaxChargingPower(timestep, inverse=True)
                    v.addPower( -1 * power, timestep)
                traj_lower.append(v.VehicleEngy) # add last energy level

                # reverse the list
                traj_lower.reverse()
            else:
                for i in range(k): #decharging for k+1 steps, with +1 steps to also note the last charge.
                    traj_lower.append(v.VehicleEngy)
                    power = v.getMaxChargingPower(timestep, inverse=True)
                    # we do assume here that the function power = f(energy) is decreasing with increasing energy level, meaning that if we "charge backwards in time" we always take a lower charging power than it would be possible. If we wanna do this with a better approximation, we should increase the discretization rate to keep the error lower, and then synthezise from that function the lower charging trajectory.
                    v.addPower( -1 * power, timestep)
                    # reverse the list
                traj_lower.append(v.VehicleEngy) # add last energy level
                traj_lower.reverse()
                # take the first N + 1 elements
                traj_lower = traj_lower[0:N+1] 
            
            # the lower charging trajectory isn't sufficient if traj_lower[0] is greater then self.VehicleEngy
            if traj_lower[0] > self.VehicleEngy:
                # use upper charging trajectory
                traj_upper = self.getChargingTrajectoryUpper(t_act, timestep, N)
                traj_lower = traj_upper.copy()
            else:
                # substract energy of t = 0
                for i in range(len(traj_lower)):
                    traj_lower[i] = traj_lower[i] - self.VehicleEngy
                traj_upper = self.getChargingTrajectoryUpper(t_act, timestep, N)

            # check that traj_lower isn't higher than the upper trajectory 
            # (left here because of coder's suspicion that there is a bug, but the coder is pretty sure that is not the case)
            for i in range(N+1):
                if traj_lower[i] > traj_upper[i]:
                    raise ValueError("Lower charging trajectory is higher than upper charging trajectory")
        del v
        traj_lower = np.array(traj_lower)
        traj_upper = np.array(traj_upper)
        return traj_lower, traj_upper
